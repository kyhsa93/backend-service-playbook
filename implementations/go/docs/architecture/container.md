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

# ---- Stage 2: Production ----
FROM gcr.io/distroless/static-debian12

COPY --from=build /bin/server /bin/server

EXPOSE 8080

USER nonroot:nonroot

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

readiness 상태 토글과 SIGTERM 연동 상세는 [graceful-shutdown.md](graceful-shutdown.md) 참조. `distroless`에는 `curl`이 없으므로 Docker `HEALTHCHECK` 지시어로 HTTP 호출을 실행하려면 별도 헬스체크 바이너리를 빌드하거나, Kubernetes의 `httpGet` 프로브(컨테이너 내부 실행 불필요)를 사용한다.

---

## 원칙

- **정적 바이너리 + `scratch`/`distroless`**: `CGO_ENABLED=0`으로 완전한 정적 링크를 만들고, 런타임이 필요 없는 최소 base 이미지를 사용한다.
- **멀티스테이지 빌드 필수**: Go SDK와 소스 코드는 프로덕션 이미지에 포함하지 않는다.
- **ENTRYPOINT는 exec form**: 셸 래퍼 없이 바이너리를 직접 PID 1로 실행한다.
- **환경 변수는 이미지 외부에서 주입**한다.
- **헬스체크 엔드포인트 필수**: liveness + readiness를 `net/http`로 직접 구현한다.

---

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM 처리, 헬스체크 상태 토글
- [config.md](config.md) — 환경 변수 검증
- [local-dev.md](local-dev.md) — 로컬 개발용 docker-compose 구성 (프로덕션 이미지와는 별개)
