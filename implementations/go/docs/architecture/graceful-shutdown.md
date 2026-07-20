# Graceful Shutdown (Go)

원칙은 루트 [graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md)를 따른다: SIGTERM 수신 시 readiness를 먼저 실패로 전환하고, 진행 중인 요청을 마친 뒤, HTTP 서버를 닫고, 그 다음에 리소스를 정리한다. Go 표준 라이브러리는 이 전체 흐름을 프레임워크 없이 `signal.NotifyContext` + `http.Server.Shutdown(ctx)` 두 가지만으로 구현한다.

`cmd/server/main.go`가 실제로 이 패턴을 쓴다 — 아래는 그 실제 코드다.

---

## 실제 구현 — `signal.NotifyContext` + `Shutdown(ctx)`

`main()`의 나머지 조립(config 검증, DB, Infrastructure, `outboxPoller`/`outboxConsumer`, `NewRouter(accountRepo, jwtService, limiter)` 등)은 [bootstrap.md](bootstrap.md)의 코드 그대로이고, 서버 시작/종료 부분만 아래와 같다:

```go
// cmd/server/main.go — 실제 코드 (마지막 부분만 발췌)
srv := &http.Server{Addr: ":8080", Handler: mux}

// SIGTERM/SIGINT를 받으면 ctx가 취소된다.
ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
defer stop()

go func() {
	slog.Info("listening", "addr", srv.Addr)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("server error", "error", err)
		os.Exit(1)
	}
}()

<-ctx.Done() // SIGTERM 수신까지 블록
slog.Info("shutdown signal received")

// terminationGracePeriodSeconds에 맞춰 여유 있게 설정 (예: 오케스트레이터 설정과 동일하게 30초)
shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
defer cancel()

// 진행 중인 요청이 끝날 때까지 대기하며 새 연결은 거부한다.
if err := srv.Shutdown(shutdownCtx); err != nil {
	slog.Error("graceful shutdown failed", "error", err)
}
slog.Info("server stopped")
// defer db.Close()가 이 시점 이후에 실행됨 — HTTP 서버가 완전히 닫힌 뒤 DB 연결 정리
```

**흐름과 root 원칙의 대응:**

| root 단계 | Go 구현 |
|---|---|
| 1. SIGTERM 수신 | `signal.NotifyContext(ctx, syscall.SIGTERM, syscall.SIGINT)` — 시그널을 컨텍스트 취소로 변환 |
| 2. readiness를 503으로 | `healthHandler.StartShutdown()`이 `atomic.Bool` 플래그를 세우고, `/health/ready` 핸들러가 이를 검사해 503 반환 — 아래 헬스체크 섹션 참고 |
| 3. 진행 중인 요청 완료 대기 | `srv.Shutdown(shutdownCtx)`가 자동으로 처리 — 새 연결은 즉시 거부하고, 기존 연결은 idle이 될 때까지 기다린다 |
| 4. HTTP 서버 종료 | `Shutdown()` 반환 시점 |
| 5. 리소스 정리 | `defer db.Close()`가 `main()` 함수 종료 시(= `Shutdown()` 반환 이후) 실행 |
| 6. 정상 종료 | `main()`이 정상 리턴하면 exit code 0 |

---

## Liveness / Readiness 엔드포인트

`internal/interface/http/health_handler.go`의 `HealthHandler`가 `/health/live`·`/health/ready`를 제공하고, `router.go`가 이 두 라우트를 rate limit 미들웨어([rate-limiting.md](rate-limiting.md))로 감싸지 않은 채 `mux`에 직접 등록한다.

```go
// internal/interface/http/health_handler.go — 실제 코드
type HealthHandler struct {
	shuttingDown atomic.Bool
}

func NewHealthHandler() *HealthHandler { return &HealthHandler{} }

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

`NewRouter`가 조립한 `*HealthHandler`를 반환하고, `main()`은 이를 붙잡아 두었다가 시그널 수신 직후 사용한다:

```go
// cmd/server/main.go — 실제 코드(발췌)
accountRepo := persistence.NewAccountRepository(db, outboxWriter)
mux, healthHandler := httphandler.NewRouter(accountRepo, jwtService, limiter)

// ...

<-ctx.Done() // SIGTERM/SIGINT 수신까지 블록
slog.Info("shutdown signal received")

// srv.Shutdown(ctx)보다 반드시 먼저 호출한다 — readiness가 503으로 바뀐 뒤에야
// 오케스트레이터가 새 트래픽을 끊으므로, HTTP 서버가 실제로 멈추기 전에 readiness부터
// 실패시켜야 무중단 전환이 된다.
healthHandler.StartShutdown()

shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
defer cancel()
if err := srv.Shutdown(shutdownCtx); err != nil {
	slog.Error("graceful shutdown failed", "error", err)
}
```

`healthHandler.StartShutdown()`을 `srv.Shutdown()`보다 **먼저** 호출하는 순서가 핵심이다 — root가 강조하는 순서(readiness 전환이 HTTP 서버 종료보다 먼저)를 지켜야, 오케스트레이터가 `/health/ready`의 503을 보고 새 트래픽 라우팅을 끊는 시점과 서버가 실제로 새 연결을 거부하기 시작하는 시점 사이에 공백이 생기지 않는다.

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
- [rate-limiting.md](rate-limiting.md) — `/health/live`·`/health/ready`를 rate limit 미들웨어에서 제외하는 라우팅 방식
