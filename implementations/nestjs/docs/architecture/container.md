# Dockerfile

### Dockerfile

```dockerfile
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

### 설계 원칙

**멀티스테이지 빌드**: Build 스테이지에서 TypeScript를 컴파일하고, Production 스테이지에는 컴파일된 JS와 프로덕션 의존성만 포함한다. 이미지 크기를 최소화한다.

| 항목 | 설명 |
|------|------|
| Base 이미지 | `node:20-alpine` — 경량 이미지 |
| Build 스테이지 | 전체 의존성 설치 + TypeScript 빌드 |
| Production 스테이지 | `--omit=dev`로 프로덕션 의존성만 설치, `dist/` 복사 |
| USER | `node` — `node:20-alpine`이 기본 제공하는 non-root 사용자 |
| EXPOSE | 3000 (환경 변수 `PORT`로 변경 가능) |
| HEALTHCHECK | `wget`(busybox, `node:20-alpine`에 기본 포함)으로 `/health/live` 조회 — [graceful-shutdown.md](graceful-shutdown.md)의 liveness 엔드포인트 |
| CMD | `node dist/main.js` — `npm run start:prod`보다 프로세스 시그널 처리에 유리 |

### 원칙

- **멀티스테이지 빌드 필수**: devDependencies와 소스 코드가 프로덕션 이미지에 포함되지 않도록 한다.
- **non-root 사용자로 실행**: `USER node` — `node:alpine`이 기본 제공하는 사용자를 그대로 쓴다. 컨테이너가 루트 권한 없이 실행되도록 하는 최소 권한 원칙이다.
- **.dockerignore 유지**: `node_modules`, `dist`, `.env*`, `.git` 등을 빌드 컨텍스트에서 제외한다.
- **CMD에 `node`를 직접 사용**: `npm run`은 중간에 npm 프로세스가 끼어 SIGTERM 전달이 지연될 수 있다.
- **환경 변수는 이미지에 포함하지 않는다**: `.env` 파일은 `.dockerignore`로 제외하고, 실행 시 `--env-file` 또는 오케스트레이션 도구에서 주입한다.
- **HEALTHCHECK는 `wget` 사용**: `node:20-alpine`은 curl이 기본 설치되어 있지 않다 — busybox에 포함된 `wget`으로 `/health/live`를 조회한다. Kubernetes/ECS처럼 오케스트레이터가 이미 liveness/readiness probe를 담당하는 배포 환경에서는 중복이라 필수는 아니다(java-springboot 구현 참고) — 단독 `docker run`/`docker ps`로 컨테이너 헬스 상태를 바로 확인하고 싶을 때 유용하다. `harness/evaluators/rules/dockerfile.evaluator.ts`는 `HEALTHCHECK` 부재를 `dockerfile.healthcheck-missing`(medium, 권장 수준)으로 잡아낸다.
