# 컨테이너 이미지

---

## 멀티스테이지 빌드

빌드 의존성과 런타임 의존성을 분리하여 프로덕션 이미지 크기를 최소화한다.

```dockerfile
# ---- Stage 1: Build ----
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci                        # devDependencies 포함 전체 설치

COPY tsconfig.json ./
COPY src ./src

RUN npm run build                 # TypeScript 컴파일

# ---- Stage 2: Production ----
FROM node:20-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev             # 프로덕션 의존성만 설치

COPY --from=build /app/dist ./dist  # 컴파일된 결과만 복사

EXPOSE 3000

CMD ["node", "dist/main.js"]
```

**각 스테이지의 목적:**

| 스테이지 | 내용 | 최종 이미지 포함 |
|---------|------|---------------|
| Build | devDependencies + 빌드 도구 + 소스 코드 | ✗ |
| Production | prodDependencies + 컴파일된 결과물 | ✓ |

---

## .dockerignore

빌드 컨텍스트에서 불필요한 파일을 제외한다. 포함하면 이미지 크기 증가, 빌드 속도 저하, 보안 위험이 생긴다.

```
node_modules    # 컨테이너 내부에서 재설치하므로 불필요
dist            # Build 스테이지에서 새로 생성하므로 불필요
.git            # 빌드에 불필요, 용량 큼
.env*           # 환경 변수를 이미지에 포함하면 보안 위험
docker-compose.yml
*.log
```

---

## CMD — 직접 프로세스 실행

```dockerfile
# 올바른 방식 — 프로세스를 PID 1로 직접 실행
CMD ["node", "dist/main.js"]

# 잘못된 방식 — npm이 중간에 끼어 SIGTERM 전달 지연
CMD ["npm", "run", "start:prod"]
CMD npm run start:prod              # shell form도 동일 문제
```

`exec form`(`["node", "..."]`)을 사용하면 node가 PID 1을 가져 SIGTERM을 즉시 수신한다. npm/yarn 래퍼를 쓰면 SIGTERM이 npm 프로세스에 전달되고 자식 프로세스(node)에는 전달되지 않거나 지연된다.

→ 상세 종료 흐름은 [graceful-shutdown.md](graceful-shutdown.md) 참조

---

## 환경 변수는 이미지에 포함 금지

```dockerfile
# 금지 — 이미지에 민감값 포함
ENV DATABASE_PASSWORD=mypassword

# 금지 — .env 파일을 이미지에 복사
COPY .env .env
```

환경 변수와 시크릿은 **컨테이너 실행 시 주입**한다. 이미지에 포함하면 이미지를 공유하거나 레지스트리에 푸시하는 순간 노출된다.

```bash
# 실행 시 주입 방법
docker run --env-file .env myapp
docker run -e DATABASE_HOST=db -e DATABASE_PASSWORD=secret myapp
```

오케스트레이션 환경에서는 Kubernetes Secret, AWS ECS Task Definition의 secrets 설정, AWS Parameter Store/Secrets Manager를 사용한다.

---

## 헬스체크 엔드포인트

컨테이너 오케스트레이터가 인스턴스 상태를 확인할 수 있도록 헬스체크 엔드포인트를 제공한다.

```
GET /health/live   → 200: 프로세스 생존 확인 (Liveness)
GET /health/ready  → 200: 트래픽 수신 가능 / 503: 종료 중 또는 초기화 중 (Readiness)
```

→ 상세 패턴은 [graceful-shutdown.md](graceful-shutdown.md) 참조

---

## 원칙

- **멀티스테이지 빌드 필수**: 빌드 도구와 devDependencies는 프로덕션 이미지에 포함하지 않는다.
- **.dockerignore 유지**: `node_modules`, `dist`, `.env*`, `.git`은 반드시 제외한다.
- **CMD에 exec form 사용**: `["node", "..."]` — SIGTERM 즉시 수신.
- **환경 변수는 이미지 외부에서 주입**: 이미지는 환경 무관하게 동일해야 한다.
- **헬스체크 엔드포인트 필수**: liveness + readiness 프로브를 모두 제공한다.

---

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM 처리, 헬스체크 패턴
- [config.md](config.md) — 환경 변수 관리
