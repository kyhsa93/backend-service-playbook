# Dockerfile

### Dockerfile

```dockerfile
# ---- Stage 1: Build ----
FROM node:24-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json tsconfig.build.json ./
COPY src ./src

RUN npm run build

# ---- Stage 2: Production ----
FROM node:24-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY --from=build /app/dist ./dist

# the node:alpine image ships a non-root user (uid 1000) for exactly this purpose by default —
# no need to create one yourself with addgroup/adduser.
USER node

EXPOSE 3000

# in a deployment environment where an orchestrator (Kubernetes, etc.) already handles the
# liveness/readiness probe, this may be redundant and unnecessary — it's useful when checking
# the container's own health directly in a standalone docker run environment.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:3000/health/live || exit 1

CMD ["node", "dist/main.js"]
```

### .dockerignore

```
node_modules
dist
.git
.env*
docker-compose.yml
localstack
```

### Design Principles

**Multi-stage build**: compile TypeScript in the Build stage, and include only the compiled JS and production dependencies in the Production stage. Minimizes the image size.

| Item | Description |
|------|------|
| Base image | `node:24-alpine` — a lightweight image |
| Build stage | installs all dependencies + builds TypeScript |
| Production stage | installs only production dependencies via `--omit=dev`, copies `dist/` |
| USER | `node` — the non-root user `node:24-alpine` provides by default |
| EXPOSE | 3000 (changeable via the `PORT` environment variable) |
| HEALTHCHECK | queries `/health/live` via `wget` (busybox, included by default in `node:24-alpine`) — the liveness endpoint from [graceful-shutdown.md](graceful-shutdown.md) |
| CMD | `node dist/main.js` — better for process signal handling than `npm run start:prod` |

### Principles

- **A multi-stage build is required**: keep devDependencies and source code out of the production image.
- **Run as a non-root user**: `USER node` — use the user `node:alpine` already provides by default. The principle of least privilege, so the container runs without root permissions.
- **Keep a .dockerignore**: exclude `node_modules`, `dist`, `.env*`, `.git`, etc. from the build context.
- **Use `node` directly in CMD**: `npm run` inserts an npm process in between, which can delay SIGTERM delivery.
- **Never bake environment variables into the image**: exclude the `.env` file via `.dockerignore`, and inject it at runtime via `--env-file` or an orchestration tool.
- **HEALTHCHECK uses `wget`**: `node:24-alpine` doesn't have curl installed by default — it queries `/health/live` using the `wget` included in busybox. In a deployment environment where an orchestrator like Kubernetes/ECS already handles the liveness/readiness probe, this is redundant and not strictly required (see the java-springboot implementation) — it's useful when you want to check the container's health status directly via a standalone `docker run`/`docker ps`. `harness/evaluators/rules/dockerfile.evaluator.ts` flags a missing `HEALTHCHECK` as `dockerfile.healthcheck-missing` (medium, a recommendation-level check).
