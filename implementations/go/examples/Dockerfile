# ---- Stage 1: Build ----
FROM golang:1.25-alpine AS build

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal

# CGO_ENABLED=0 — produces a fully static binary that doesn't dynamically
# link against libc. Required since scratch/distroless has no libc.
RUN CGO_ENABLED=0 GOOS=linux go build -o /bin/server ./cmd/server

# Static binary dedicated to HEALTHCHECK — distroless has no curl/wget, so
# there's no way to run an HTTP health check, so this is compiled directly
# and bundled into the final image.
RUN CGO_ENABLED=0 GOOS=linux go build -o /bin/healthcheck ./cmd/healthcheck

# ---- Stage 2: Production ----
FROM gcr.io/distroless/static-debian12

COPY --from=build /bin/server /bin/server
COPY --from=build /bin/healthcheck /bin/healthcheck

EXPOSE 8080

USER nonroot:nonroot

# exec form is required — distroless has no shell, so shell form
# (`HEALTHCHECK CMD curl ...`) can't be run at all. /bin/healthcheck calls
# localhost:8080/health/live inside the container and reports status via its
# exit code (0=healthy, 1=unhealthy).
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD ["/bin/healthcheck"]

ENTRYPOINT ["/bin/server"]
