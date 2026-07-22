# Container Image (Go)

The principle follows the root [container.md](../../../../docs/architecture/container.md): multi-stage build, `.dockerignore`, exec-form CMD, no environment variables baked into the image, healthcheck endpoints. Since Go **compiles to a single statically linked binary**, these principles can be satisfied far more simply than in other language implementations.

---

## Go's advantage — a single static binary with no runtime

Unlike Node.js (runtime + `node_modules`) or JVM (runtime + JAR) implementations, Go statically links every dependency into one binary produced by `go build`. The production image needs **no runtime, no package manager, and no source code** — a single compiled binary is enough to run it. This lets the production stage's base image be `scratch` (a completely empty image) or `distroless`, making the image size and attack surface dramatically smaller than in other languages.

```
Node.js production image  : node:20-alpine (~50MB) + node_modules + dist/
Go production image       : scratch (~0MB) + a single binary (~10-20MB)
```

---

## Multi-stage build

```dockerfile
# ---- Stage 1: Build ----
FROM golang:1.25-alpine AS build

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal

# CGO_ENABLED=0 — produces a fully static binary that doesn't dynamically link against libc.
# Required since scratch/distroless has no libc.
RUN CGO_ENABLED=0 GOOS=linux go build -o /bin/server ./cmd/server

# a dedicated static binary just for HEALTHCHECK — distroless has no curl/wget, so it's compiled and included directly.
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

| Stage | Contents | Included in final image |
|---------|------|---------------|
| Build | Go SDK, source code, compilation cache | No |
| Production | Only the compiled single binary | Yes |

**Base image selection criteria:**

| Base image | Characteristics | Selection criteria |
|------------|------|----------|
| `scratch` | A completely empty image. No libc, shell, or CA certificates | When a minimal image is the top priority and there are no outbound HTTPS calls |
| `gcr.io/distroless/static` | Includes CA certificates and timezone data. No shell | **Recommended default** — fits this repository's example, which makes HTTPS calls via the AWS SDK (SES/S3, etc.) |
| `alpine` | Includes a shell (`sh`) and a package manager | When debugging inside the container (`docker exec`) is frequently needed |

Since this repository's `internal/infrastructure/notification` connects to AWS SES over HTTPS, `distroless/static` — which includes CA certificates by default — fits better than `scratch`. To use `scratch`, `/etc/ssl/certs/ca-certificates.crt` would need to be copied in explicitly from the build stage.

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

Since a Go build only downloads dependencies declared in `go.mod`, there's no counterpart to exclude for Node's `node_modules`. Instead, test files (`*_test.go`, `test/`) and local-development-only files are excluded to keep the build context light.

---

## ENTRYPOINT — run directly with exec form

```dockerfile
# correct — run the binary directly as PID 1
ENTRYPOINT ["/bin/server"]

# incorrect — wrapping it in a shell script can leave SIGTERM stuck in the script
ENTRYPOINT ["sh", "-c", "/bin/server"]
```

A Go binary is already a single process, so the wrapper problem that affects npm/yarn simply doesn't arise. Still, wrapping it with `sh -c` makes the shell PID 1 and reproduces the same problem, so `ENTRYPOINT` should always be written as an exec-form array. See [graceful-shutdown.md](graceful-shutdown.md) for how the application handles SIGTERM via `signal.NotifyContext`.

---

## Environment variables are never baked into the image

```dockerfile
# forbidden
ENV DATABASE_URL=postgres://prod-user:prod-pass@...
```

They are injected at container runtime.

```bash
docker run --env-file .env.docker myapp
```

In Kubernetes/ECS environments, they're injected from a Secret/Parameter Store. See [config.md](config.md) for Go-side configuration validation (fail-fast).

---

## Healthcheck endpoints

```
GET /health/live   → 200: confirms the process is alive
GET /health/ready  → 200: ready to receive traffic / 503: shutting down
```

Implemented directly with `net/http` — no separate framework needed:

```go
mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK) // always 200
})
```

See [graceful-shutdown.md](graceful-shutdown.md) for details on toggling readiness state and its integration with SIGTERM.

---

## Dockerfile HEALTHCHECK — a dedicated static binary

`gcr.io/distroless/static-debian12` has neither a shell nor `curl`/`wget`, so a `HEALTHCHECK CMD curl -f ...`-style command, as used in other language implementations, can't be used as-is. Three alternatives were considered.

1. **Compile a dedicated healthcheck binary in the build stage and include it in the final image** — adopted
2. Switch the base image to `distroless/static-debian12:debug` (includes a busybox shell) or `alpine` to use `wget`
3. Skip introducing HEALTHCHECK altogether and defer to the orchestrator (K8s liveness/readiness probes)

**Why option 1 was chosen**: the reason the Go implementation is the only one in this repository that can use a runtime-free minimal base image like `scratch`/`distroless` is precisely because it's a static binary (see the "Go's advantage" section above). Switching to `:debug` or `alpine` (option 2) would mean giving up that advantage voluntarily and widening the attack surface, which doesn't fit the direction of the Go implementation. Option 3 (deferring to the orchestrator) is also a valid choice, and `java-springboot` actually takes that stance — but when running standalone via `docker run`, or running local integration tests with docker-compose (see [local-dev.md](local-dev.md)), there's no orchestrator, leaving no way to observe container state. Since compiling one extra healthcheck binary costs Go almost nothing (no extra dependency, hundreds of ms of build time, a few extra MB in the final image), option 1 is clearly superior to the other two alternatives.

`cmd/healthcheck/main.go` is written using only the standard library's `net/http`, with no framework or external dependency:

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

Shell form (`HEALTHCHECK CMD curl -f ...`), where the shell has to parse the `CMD` string, simply can't run under distroless at all, so it must always be written as an exec-form array (`["/bin/healthcheck"]`).

---

## Principles

- **Static binary + `scratch`/`distroless`**: produce a fully statically linked binary with `CGO_ENABLED=0`, and use a minimal base image that needs no runtime.
- **Multi-stage build is mandatory**: the Go SDK and source code are never included in the production image.
- **Run as a non-root user**: `USER nonroot:nonroot` — use the user `distroless/static` provides by default.
- **ENTRYPOINT is exec form**: run the binary directly as PID 1 with no shell wrapper.
- **Environment variables are injected from outside the image.**
- **Healthcheck endpoints are mandatory**: implement liveness + readiness directly with `net/http`.
- **Dockerfile HEALTHCHECK is implemented as a dedicated static binary**: since distroless has no curl/wget, build `cmd/healthcheck` and include it, running it in exec form.

Whether there's a multi-stage build (2 or more `FROM`s), a HEALTHCHECK, a `USER` (non-root execution), and a `.dockerignore` that excludes `.git`/`.env` is automatically checked by `implementations/go/harness/dockerfile_conventions.go` (the `dockerfile-conventions` rule), which reads `examples/Dockerfile` and `examples/.dockerignore` directly — unlike other rules, it doesn't recursively scan the Go source tree, targeting only these two files.

---

### Related documents

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM handling, toggling healthcheck state
- [config.md](config.md) — environment variable validation
- [local-dev.md](local-dev.md) — docker-compose setup for local development (separate from the production image)
