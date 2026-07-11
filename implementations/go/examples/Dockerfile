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

# HEALTHCHECK 전용 정적 바이너리 — distroless에는 curl/wget이 없어 HTTP 헬스체크를
# 실행할 방법이 없으므로, 직접 컴파일해 최종 이미지에 함께 넣는다.
RUN CGO_ENABLED=0 GOOS=linux go build -o /bin/healthcheck ./cmd/healthcheck

# ---- Stage 2: Production ----
FROM gcr.io/distroless/static-debian12

COPY --from=build /bin/server /bin/server
COPY --from=build /bin/healthcheck /bin/healthcheck

EXPOSE 8080

USER nonroot:nonroot

# exec form 필수 — distroless에는 셸이 없어 shell form(`HEALTHCHECK CMD curl ...`)은
# 애초에 실행 불가능하다. /bin/healthcheck가 컨테이너 내부 localhost:8080/health/live를
# 호출해 exit code로 상태를 알린다(0=healthy, 1=unhealthy).
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD ["/bin/healthcheck"]

ENTRYPOINT ["/bin/server"]
