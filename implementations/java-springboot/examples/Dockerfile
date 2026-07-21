# ---- Stage 1: Build ----
FROM gradle:8.14.5-jdk21-alpine AS build

WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true    # 의존성만 먼저 캐시

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Extract layers ----
FROM eclipse-temurin:21-jre-alpine AS extract

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# Spring Boot 3.3+에서 layertools jarmode는 tools jarmode로 대체되었다(Boot 4에서는
# layertools 자체가 없어져 "Unsupported jarmode" 에러가 난다) — --layers를 주면 동일한
# 4-디렉토리 레이어 구조(dependencies/spring-boot-loader/snapshot-dependencies/application)를
# --destination 아래에 만들어준다.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- Stage 3: Runtime ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 변경 빈도가 낮은 순 -> 높은 순으로 COPY (캐시 재사용 극대화)
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
