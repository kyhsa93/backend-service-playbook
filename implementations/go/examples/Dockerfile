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
