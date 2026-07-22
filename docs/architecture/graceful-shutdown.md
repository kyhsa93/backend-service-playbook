# Graceful Shutdown

In a container orchestration environment (Kubernetes, ECS, etc.), this is the pattern for safely finishing in-flight requests before shutting down when a SIGTERM is received.

---

## Shutdown sequence

```
1. The orchestrator sends SIGTERM
2. The readiness probe flips to failing immediately → the load balancer stops sending new traffic
3. Wait for in-flight requests to finish
4. Shut down the HTTP server
5. Clean up resources — DB connections, message queue connections, etc.
6. The process exits cleanly (exit code 0)
```

**The order matters.** The readiness flip (step 2) must happen before the HTTP server shuts down (step 4), so no new requests reach an instance that's shutting down.

---

## Liveness vs. Readiness probes

| Probe | Purpose | On failure | Response while shutting down |
|--------|------|------------|------------|
| **Liveness** `/health/live` | Is the process alive | The container is restarted | **200** (still alive, even while shutting down) |
| **Readiness** `/health/ready` | Is it ready to receive traffic | Removed from the load balancer | **503** (blocks new traffic) |

**A common mistake:** if Liveness also returns 503 while shutting down, the container gets restarted mid-shutdown. Liveness must always return 200.

```
// conceptual — toggling readiness state
isShuttingDown = false

// on receiving SIGTERM
isShuttingDown = true

// GET /health/ready
if (isShuttingDown) return 503
return 200

// GET /health/live
return 200  // always
```

---

## terminationGracePeriodSeconds

This is how long the orchestrator waits after SIGTERM before sending SIGKILL.

```
SIGTERM → [terminationGracePeriodSeconds] → SIGKILL (force kill)
```

**How to set it:** give it comfortable headroom over the service's p99 request-processing time. 30 seconds is usually enough. If there are batch/scheduled jobs, factor in their maximum processing time.

```yaml
# Kubernetes example
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - livenessProbe:
        httpGet:
          path: /health/live
    - readinessProbe:
        httpGet:
          path: /health/ready
```

---

## Run the process directly in the container

```dockerfile
# correct — runs the process directly as PID 1
CMD ["node", "dist/main.js"]

# wrong — npm sits in between and delays SIGTERM delivery
CMD ["npm", "run", "start:prod"]
```

If npm/yarn is used as a wrapper, SIGTERM is delivered to the npm process, and delivery to the application itself may be delayed or may never happen. Running it directly gives the application PID 1, so it receives SIGTERM immediately.

---

## Resource-cleanup principle

Cleaning up resources on shutdown runs **after the HTTP server has closed**. This is because in-flight requests still need to be able to use the DB.

```
✓ Shut down the HTTP server → release the DB connection   (correct order)
✗ Release the DB connection → shut down the HTTP server   (in-flight requests can't use the DB)
```

Don't throw exceptions during cleanup. If an exception is thrown during a cleanup step, cleanup of the other resources gets skipped. Wrap it in try-catch and just log it.

---

## Principles

- **Flip readiness first**: the instant SIGTERM is received, flip readiness to 503 to block new traffic.
- **Liveness is always 200**: liveness stays 200 even while shutting down. Changing it to 503 causes the container to be restarted.
- **Clean up resources after the HTTP server shuts down**: keep this order so in-flight requests can still use the DB.
- **Run the process directly**: receive SIGTERM immediately, with no npm/yarn wrapper.
- **Give `terminationGracePeriodSeconds` headroom over the p99 processing time**: 30 seconds by default.

---

### Related docs

- [container.md](container.md) — the Dockerfile CMD setting
- [observability.md](observability.md) — health-check logging
