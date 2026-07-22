# ---- Stage 1: Build ----
FROM gradle:8.14.5-jdk21-alpine AS build

WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true    # cache dependencies first

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Extract layers ----
FROM eclipse-temurin:21-jre-alpine AS extract

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# In Spring Boot 3.3+, the layertools jarmode was replaced by the tools jarmode (in Boot 4,
# layertools itself is gone, causing an "Unsupported jarmode" error) — passing --layers produces
# the same 4-directory layer structure (dependencies/spring-boot-loader/snapshot-dependencies/
# application) under --destination.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- Stage 3: Runtime ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# COPY from lowest to highest change frequency (maximizes cache reuse)
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
