# ---- Stage 1: Build ----
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json tsconfig.build.json ./
COPY src ./src

RUN npm run build

# ---- Stage 2: Production ----
FROM node:20-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY --from=build /app/dist ./dist

# The node:alpine image ships a non-root user (uid 1000) for exactly this purpose by default —
# no need to create one yourself with addgroup/adduser.
USER node

EXPOSE 3000

# In a deployment environment where an orchestrator (Kubernetes, etc.) already handles the
# liveness/readiness probe, this may be redundant and unnecessary — it's useful when checking the container's own health directly in a standalone docker run environment.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:3000/health/live || exit 1

CMD ["node", "dist/main.js"]
