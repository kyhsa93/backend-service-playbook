# Graceful Shutdown (Go)

원칙은 루트 [graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md)를 따른다: SIGTERM 수신 시 readiness를 먼저 실패로 전환하고, 진행 중인 요청을 마친 뒤, HTTP 서버를 닫고, 그 다음에 리소스를 정리한다. Go 표준 라이브러리는 이 전체 흐름을 프레임워크 없이 `signal.NotifyContext` + `http.Server.Shutdown(ctx)` 두 가지만으로 구현한다.

**현재 `cmd/server/main.go`는 이 패턴을 전혀 쓰지 않는다** — `http.ListenAndServe`를 블로킹 호출하고 끝이다. 이 문서는 root 원칙을 만족하는 목표 구현을 제시한다.

---

## 현재 코드 — 알려진 격차

```go
// cmd/server/main.go — 현재
addr := ":8080"
log.Printf("listening on %s", addr)
if err := http.ListenAndServe(addr, mux); err != nil {
	log.Fatalf("server error: %v", err)
}
```

`ListenAndServe`는 SIGTERM을 받아도 스스로 종료하지 않는다 — 오케스트레이터가 grace period 이후 SIGKILL을 보내면 진행 중이던 요청이 강제로 끊긴다.

---

## 목표 구현 — `signal.NotifyContext` + `Shutdown(ctx)`

```go
// cmd/server/main.go — 목표 형태
package main

import (
	"context"
	"database/sql"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/lib/pq"

	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/persistence"
	httphandler "github.com/example/account-service/internal/interface/http"
)

func main() {
	db, err := sql.Open("postgres", os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
	}
	defer db.Close()

	accountRepo := persistence.NewAccountRepository(db)
	notifier := notification.NewService(notification.NewSESClient(), db)
	mux := httphandler.NewRouter(accountRepo, notifier)

	srv := &http.Server{Addr: ":8080", Handler: mux}

	// SIGTERM/SIGINT를 받으면 ctx가 취소된다.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		log.Printf("listening on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server error: %v", err)
		}
	}()

	<-ctx.Done() // SIGTERM 수신까지 블록
	log.Println("shutdown signal received")

	// terminationGracePeriodSeconds에 맞춰 여유 있게 설정 (예: 오케스트레이터 설정과 동일하게 30초)
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// 진행 중인 요청이 끝날 때까지 대기하며 새 연결은 거부한다.
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("graceful shutdown failed: %v", err)
	}
	log.Println("server stopped")
	// defer db.Close()가 이 시점 이후에 실행됨 — HTTP 서버가 완전히 닫힌 뒤 DB 연결 정리
}
```

**흐름과 root 원칙의 대응:**

| root 단계 | Go 구현 |
|---|---|
| 1. SIGTERM 수신 | `signal.NotifyContext(ctx, syscall.SIGTERM, syscall.SIGINT)` — 시그널을 컨텍스트 취소로 변환 |
| 2. readiness를 503으로 | `/health/ready` 핸들러가 `ctx.Err() != nil`(또는 별도 `atomic.Bool` 플래그)을 검사해 503 반환 — 아래 헬스체크 섹션 참고 |
| 3. 진행 중인 요청 완료 대기 | `srv.Shutdown(shutdownCtx)`가 자동으로 처리 — 새 연결은 즉시 거부하고, 기존 연결은 idle이 될 때까지 기다린다 |
| 4. HTTP 서버 종료 | `Shutdown()` 반환 시점 |
| 5. 리소스 정리 | `defer db.Close()`가 `main()` 함수 종료 시(= `Shutdown()` 반환 이후) 실행 |
| 6. 정상 종료 | `main()`이 정상 리턴하면 exit code 0 |

---

## Liveness / Readiness 엔드포인트

```go
// internal/interface/http/health_handler.go — 목표 형태
type HealthHandler struct {
	shuttingDown atomic.Bool
}

func (h *HealthHandler) StartShutdown() { h.shuttingDown.Store(true) }

func (h *HealthHandler) Live(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK) // 항상 200 — 종료 중이어도 프로세스는 살아있다
}

func (h *HealthHandler) Ready(w http.ResponseWriter, r *http.Request) {
	if h.shuttingDown.Load() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}
```

`main()`의 시그널 수신 직후, `srv.Shutdown()`을 호출하기 **전에** `healthHandler.StartShutdown()`을 먼저 호출해야 한다 — root가 강조하는 순서(readiness 전환이 HTTP 서버 종료보다 먼저)를 지키기 위해서다.

---

## 컨테이너에서 직접 프로세스 실행

```dockerfile
# 올바른 방식 — Go 바이너리를 PID 1로 직접 실행 (container.md 참고)
CMD ["/app/server"]
```

Go 바이너리는 애초에 셸 스크립트나 `npm`/`yarn` 같은 런처가 필요 없다 — 컴파일된 단일 실행 파일을 `CMD`의 exec form으로 직접 실행하면 그 프로세스가 PID 1이 되어 SIGTERM을 지연 없이 즉시 수신한다. Node/npm처럼 "래퍼 프로세스가 시그널을 삼키는" 문제가 Go에는 원천적으로 없다.

---

## `terminationGracePeriodSeconds`

`context.WithTimeout(context.Background(), 30*time.Second)`의 30초 값은 오케스트레이터(Kubernetes 등)의 `terminationGracePeriodSeconds`와 맞춰야 한다 — Go 쪽 타임아웃이 오케스트레이터의 grace period보다 길면, 서버가 아직 graceful shutdown 중인데 SIGKILL이 먼저 와서 강제 종료된다.

---

### 관련 문서

- [container.md](container.md) — Dockerfile CMD 설정, 헬스체크 엔드포인트 노출
- [observability.md](observability.md) — 종료 과정의 구조화 로깅
- [scheduling.md](scheduling.md) — 백그라운드 워커(Ticker)의 graceful shutdown
