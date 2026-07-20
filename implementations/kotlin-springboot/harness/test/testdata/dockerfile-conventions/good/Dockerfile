# ---- Stage 1: Build ----
FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /app
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

CMD ["java", "-jar", "app.jar"]
