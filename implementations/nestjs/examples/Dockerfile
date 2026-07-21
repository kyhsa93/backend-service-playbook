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

# node:alpine 이미지는 이 목적을 위한 non-root 사용자(uid 1000)를 기본 제공한다 —
# 별도로 addgroup/adduser를 만들 필요가 없다.
USER node

EXPOSE 3000

# 오케스트레이터(Kubernetes 등)가 이미 liveness/readiness probe를 담당하는 배포 환경에서는
# 중복이라 불필요할 수 있다 — 단독 docker run 환경에서 컨테이너 자체 헬스 상태를 확인할 때 유용하다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:3000/health/live || exit 1

CMD ["node", "dist/main.js"]
