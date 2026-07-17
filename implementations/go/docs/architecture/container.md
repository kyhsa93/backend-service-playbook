# 컨테이너 이미지 (Go)

원칙은 루트 [container.md](../../../../docs/architecture/container.md)를 따른다: 멀티스테이지 빌드, `.dockerignore`, exec form CMD, 이미지에 환경 변수 미포함, 헬스체크 엔드포인트. Go는 **정적 링크된 단일 바이너리로 컴파일**되므로 다른 언어 구현체보다 이 원칙들을 훨씬 단순하게 만족시킬 수 있다.

---

## Go의 이점 — 런타임 없는 단일 정적 바이너리

Node.js(런타임 + `node_modules`)나 JVM(런타임 + JAR) 구현체와 달리, Go는 `go build`로 만든 바이너리 하나에 모든 의존성이 정적으로 링크된다. 프로덕션 이미지에는 **런타임도, 패키지 매니저도, 소스 코드도 필요 없다** — 컴파일된 바이너리 하나만 있으면 실행된다. 이 덕분에 프로덕션 스테이지의 base 이미지로 `scratch`(완전히 빈 이미지) 또는 `distroless`를 선택할 수 있어, 다른 언어 대비 이미지 크기와 공격 표면이 극단적으로 작아진다.

```
Node.js 프로덕션 이미지  : node:20-alpine (~50MB) + node_modules + dist/
Go 프로덕션 이미지       : scratch (~0MB) + 단일 바이너리 (~10-20MB)
```

---

## 멀티스테이지 빌드

```dockerfile
# ---- Stage 1: Build ----
FROM golang:1.25-alpine AS build

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal

# CGO_ENABLED=0 — libc에 동적 링크하지 않는 완전한 정적 바이너리를 만든다.
# scratch/distroless에는 libc가 없으므로 필수.
RUN CGO_ENABLED=0 GOOS=linux go build -o /bin/server ./cmd/server

# HEALTHCHECK 전용 정적 바이너리 — distroless에는 curl/wget이 없어 직접 컴파일해 포함한다.
RUN CGO_ENABLED=0 GOOS=linux go build -o /bin/healthcheck ./cmd/healthcheck

# ---- Stage 2: Production ----
FROM gcr.io/distroless/static-debian12

COPY --from=build /bin/server /bin/server
COPY --from=build /bin/healthcheck /bin/healthcheck

EXPOSE 8080

USER nonroot:nonroot

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD ["/bin/healthcheck"]

ENTRYPOINT ["/bin/server"]
```

| 스테이지 | 내용 | 최종 이미지 포함 |
|---------|------|---------------|
| Build | Go SDK, 소스 코드, 컴파일 캐시 | ✗ |
| Production | 컴파일된 단일 바이너리만 | ✓ |

**base 이미지 선택 기준:**

| base 이미지 | 특징 | 선택 기준 |
|------------|------|----------|
| `scratch` | 완전히 빈 이미지. libc, 셸, CA 인증서 없음 | 최소 이미지가 최우선이고 외부 HTTPS 호출이 없는 경우 |
| `gcr.io/distroless/static` | CA 인증서, 타임존 데이터 포함. 셸 없음 | **권장 기본값** — SES/S3 등 AWS SDK로 HTTPS 호출하는 이 저장소 예제에 적합 |
| `alpine` | 셸(`sh`), 패키지 매니저 포함 | 컨테이너 내부에서 디버깅(`docker exec`)이 자주 필요한 경우 |

이 저장소의 `internal/infrastructure/notification`이 AWS SES에 HTTPS로 접속하므로, CA 인증서가 기본 포함된 `distroless/static`이 `scratch`보다 적합하다 — `scratch`를 쓰려면 빌드 스테이지에서 `/etc/ssl/certs/ca-certificates.crt`를 직접 복사해야 한다.

---

## .dockerignore

```
.git
*_test.go
test/
docker-compose.yml
localstack/
.env*
*.md
```

Go 빌드는 `go.mod`에 선언된 의존성만 다운로드하므로 Node의 `node_modules`에 해당하는 제외 대상이 없다. 대신 테스트 파일(`*_test.go`, `test/`)과 로컬 개발 전용 파일을 제외해 빌드 컨텍스트를 가볍게 유지한다.

---

## ENTRYPOINT — exec form으로 직접 실행

```dockerfile
# 올바른 방식 — 바이너리를 PID 1로 직접 실행
ENTRYPOINT ["/bin/server"]

# 잘못된 방식 — 쉘 스크립트로 감싸면 SIGTERM이 스크립트에서 멈출 수 있음
ENTRYPOINT ["sh", "-c", "/bin/server"]
```

Go 바이너리는 이미 단일 프로세스이므로 npm/yarn 같은 래퍼 문제 자체가 발생하지 않는다. 다만 `sh -c`로 감싸면 셸이 PID 1이 되어 동일한 문제가 재현되므로 `ENTRYPOINT`는 항상 exec form 배열로 작성한다. 애플리케이션이 `signal.NotifyContext`로 SIGTERM을 처리하는 방법은 [graceful-shutdown.md](graceful-shutdown.md) 참조.

---

## 환경 변수는 이미지에 포함하지 않는다

```dockerfile
# 금지
ENV DATABASE_URL=postgres://prod-user:prod-pass@...
```

컨테이너 실행 시 주입한다.

```bash
docker run --env-file .env.docker myapp
```

Kubernetes/ECS 환경에서는 Secret/Parameter Store에서 주입한다. Go 쪽 설정 검증(fail-fast)은 [config.md](config.md) 참조.

---

## 헬스체크 엔드포인트

```
GET /health/live   → 200: 프로세스 생존 확인
GET /health/ready  → 200: 트래픽 수신 가능 / 503: 종료 중
```

`net/http`로 직접 구현한다 — 별도 프레임워크 불필요:

```go
mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK) // 항상 200
})
```

readiness 상태 토글과 SIGTERM 연동 상세는 [graceful-shutdown.md](graceful-shutdown.md) 참조.

---

## Dockerfile HEALTHCHECK — 전용 정적 바이너리

`gcr.io/distroless/static-debian12`는 셸도 `curl`/`wget`도 없어 다른 언어 구현체처럼 `HEALTHCHECK CMD curl -f ...` 형태를 그대로 쓸 수 없다. 검토한 대안은 세 가지다.

1. **빌드 스테이지에서 전용 헬스체크 바이너리를 컴파일해 최종 이미지에 포함** — 채택
2. base 이미지를 `distroless/static-debian12:debug`(busybox 셸 포함) 또는 `alpine`으로 교체해 `wget` 사용
3. HEALTHCHECK 자체를 도입하지 않고 오케스트레이터(K8s liveness/readiness probe)에 위임

**1번을 채택한 이유**: Go 구현체가 이 저장소에서 유일하게 `scratch`/`distroless` 같은 런타임-없는 최소 base 이미지를 쓸 수 있는 이유는 정적 바이너리이기 때문이다(위 "Go의 이점" 절 참조). `:debug`나 `alpine`으로 바꾸면(2번) 이 이점을 스스로 포기하고 공격 표면을 넓히는 셈이라 Go 구현체의 방향성과 맞지 않는다. 3번(오케스트레이터 위임)도 유효한 선택지이고 실제로 `java-springboot`가 이 입장이지만, `docker run`으로 단독 실행하거나 docker-compose로 로컬 통합 테스트를 돌릴 때(→ [local-dev.md](local-dev.md))는 오케스트레이터가 없어 컨테이너 상태를 볼 방법이 없다. Go는 헬스체크 바이너리 하나를 추가로 컴파일하는 비용이 거의 0(추가 의존성 없음, 빌드 시간 수백 ms, 최종 이미지에 수 MB 추가)이라 1번이 다른 두 대안보다 확실히 우월하다.

`cmd/healthcheck/main.go`는 프레임워크나 외부 의존성 없이 표준 라이브러리 `net/http`만으로 작성한다:

```go
func main() {
	client := &http.Client{Timeout: 2 * time.Second}

	resp, err := client.Get("http://localhost:8080/health/live")
	if err != nil {
		os.Exit(1)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}
```

`CMD` 문자열을 셸이 파싱해야 하는 shell form(`HEALTHCHECK CMD curl -f ...`)은 애초에 distroless에서 실행 불가능하므로 반드시 exec form(`["/bin/healthcheck"]`) 배열로 작성한다.

---

## 원칙

- **정적 바이너리 + `scratch`/`distroless`**: `CGO_ENABLED=0`으로 완전한 정적 링크를 만들고, 런타임이 필요 없는 최소 base 이미지를 사용한다.
- **멀티스테이지 빌드 필수**: Go SDK와 소스 코드는 프로덕션 이미지에 포함하지 않는다.
- **ENTRYPOINT는 exec form**: 셸 래퍼 없이 바이너리를 직접 PID 1로 실행한다.
- **환경 변수는 이미지 외부에서 주입**한다.
- **헬스체크 엔드포인트 필수**: liveness + readiness를 `net/http`로 직접 구현한다.
- **Dockerfile HEALTHCHECK는 전용 정적 바이너리로 구현**: distroless에는 curl/wget이 없으므로 `cmd/healthcheck`를 빌드해 포함하고 exec form으로 실행한다.

---

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM 처리, 헬스체크 상태 토글
- [config.md](config.md) — 환경 변수 검증
- [local-dev.md](local-dev.md) — 로컬 개발용 docker-compose 구성 (프로덕션 이미지와는 별개)
