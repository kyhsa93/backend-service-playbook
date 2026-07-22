# ---- Stage 1: Build ----
FROM gradle:8.10-jdk21-alpine AS build

WORKDIR /app

# Copy the dependency layer before the source code — if only the source changes, the dependency download is skipped via cache
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Run as a non-root user — minimizes damage scope if the container is breached
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

# A HEALTHCHECK could be added to the Dockerfile, but the orchestrator's liveness/readiness probe
# already covers this, so it would be redundant in a Kubernetes/ECS environment. It's only useful in a standalone docker run environment.
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

CMD ["java", "-jar", "app.jar"]
