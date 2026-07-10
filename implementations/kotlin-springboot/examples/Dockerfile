# ---- Stage 1: Build ----
FROM gradle:8.10-jdk21-alpine AS build

WORKDIR /app

# 의존성 레이어를 소스 코드보다 먼저 복사 — 소스만 바뀌면 의존성 다운로드를 캐시로 스킵
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# non-root 사용자로 실행 — 컨테이너 탈출 시 피해 범위 최소화
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
