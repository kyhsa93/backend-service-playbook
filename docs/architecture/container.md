# Container Images

---

## Multi-stage builds

Separate build-time dependencies from runtime dependencies to keep the production image as small as possible.

```dockerfile
# ---- Stage 1: Build ----
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci                        # install everything, including devDependencies

COPY tsconfig.json ./
COPY src ./src

RUN npm run build                 # compile TypeScript

# ---- Stage 2: Production ----
FROM node:20-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev             # install only production dependencies

COPY --from=build /app/dist ./dist  # copy just the compiled output

# The node:alpine image ships a non-root user (uid 1000) for exactly this purpose.
USER node

EXPOSE 3000

CMD ["node", "dist/main.js"]
```

**What each stage is for:**

| Stage | Contents | In the final image |
|---------|------|---------------|
| Build | devDependencies + build tools + source code | ✗ |
| Production | prodDependencies + compiled output | ✓ |

---

## .dockerignore

Exclude unnecessary files from the build context. Including them grows the image size, slows the build, and creates a security risk.

```
node_modules    # unnecessary — reinstalled inside the container
dist            # unnecessary — regenerated fresh in the Build stage
.git            # unnecessary for the build, and large
.env*           # a security risk if env vars end up in the image
docker-compose.yml
*.log
```

---

## CMD — run the process directly

```dockerfile
# correct — runs the process directly as PID 1
CMD ["node", "dist/main.js"]

# wrong — npm sits in between and delays SIGTERM delivery
CMD ["npm", "run", "start:prod"]
CMD npm run start:prod              # shell form has the same problem
```

Using `exec form` (`["node", "..."]`) gives node PID 1, so it receives SIGTERM immediately. With an npm/yarn wrapper, SIGTERM is delivered to the npm process and either delayed or never delivered to the child process (node).

→ See [graceful-shutdown.md](graceful-shutdown.md) for the detailed shutdown flow

---

## Never put environment variables in the image

```dockerfile
# forbidden — bakes a sensitive value into the image
ENV DATABASE_PASSWORD=mypassword

# forbidden — copies a .env file into the image
COPY .env .env
```

Environment variables and secrets are **injected when the container runs**. Baking them into the image exposes them the moment the image is shared or pushed to a registry.

```bash
# how to inject them at runtime
docker run --env-file .env myapp
docker run -e DATABASE_HOST=db -e DATABASE_PASSWORD=secret myapp
```

In an orchestrated environment, use a Kubernetes Secret, an AWS ECS Task Definition's `secrets` setting, or AWS Parameter Store/Secrets Manager.

---

## Health-check endpoints

Provide health-check endpoints so the container orchestrator can check an instance's status.

```
GET /health/live   → 200: confirms the process is alive (Liveness)
GET /health/ready  → 200: ready for traffic / 503: shutting down or still initializing (Readiness)
```

→ See [graceful-shutdown.md](graceful-shutdown.md) for the detailed pattern

---

## Principles

- **Multi-stage builds are required**: don't include build tools or devDependencies in the production image.
- **Run as a non-root user**: switch to a dedicated user so the container runs with no root privileges (use the base image's default one if it provides one; otherwise create one).
- **Keep a .dockerignore**: always exclude `node_modules`, `dist`, `.env*`, `.git`.
- **Use exec form for CMD**: `["node", "..."]` — receives SIGTERM immediately.
- **Inject environment variables from outside the image**: the image should be identical regardless of environment.
- **Health-check endpoints are required**: provide both liveness and readiness probes.

---

### Related docs

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM handling, the health-check pattern
- [config.md](config.md) — managing environment variables
