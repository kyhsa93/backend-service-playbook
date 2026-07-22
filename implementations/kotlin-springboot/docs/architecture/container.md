# Container Image — Kotlin Spring Boot

> For the framework-agnostic principles, see [root container.md](../../../../docs/architecture/container.md).

## Dockerfile + Actuator health check

`implementations/kotlin-springboot/examples/Dockerfile` implements the multi-stage build below as the actual code (Gradle Kotlin DSL build, Kotlin 1.9.25 + Spring Boot 3.3.5 + Java 21 toolchain). `.dockerignore` also exists with exactly the content shown below. The Actuator-based liveness/readiness probes are configured via the `spring-boot-starter-actuator` dependency in `build.gradle.kts` and the `management.*` settings in `application.yml` (see the "Health check endpoint" section below).

---

## Multi-stage build

Because running Gradle itself is heavy for a Kotlin/JVM build (the daemon, the dependency cache), **the build stage and runtime stage are separated so the final image contains only a JRE + the build artifact (JAR), not the entire JDK**.

```dockerfile
# examples/Dockerfile — actual code
# ---- Stage 1: Build ----
FROM gradle:8.10-jdk21-alpine AS build

WORKDIR /app

# Copy the dependency layer before the source code — if only the source changes, the dependency
# download is skipped via the cache
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Run as a non-root user — minimizes the blast radius if the container is escaped
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
```

**Purpose of each stage:**

| Stage | Contents | Included in final image |
|---------|------|---------------|
| Build | Gradle + Kotlin compiler + full source + dependency cache | ✗ |
| Runtime | JRE (not JDK) + compiled `app.jar` | ✓ |

Using a **JRE (execution only)** base image instead of a JDK (which includes development tools) is an optimization specific to the Java/Kotlin ecosystem — Node.js has no equivalent distinction.

---

## .dockerignore

```
# examples/.dockerignore — actual code
.gradle/
build/
*.jar
.git
.env*
docker-compose.yml
*.log
```

Excluding `.gradle/` (the local cache) and `build/` (previous build artifacts) from the build context improves both context-transfer speed and cache reliability. Since `gradle bootJar` runs again inside the container, the host's `build/` artifacts aren't needed.

---

## CMD — running the JVM directly as PID 1 in exec form

```dockerfile
# correct — the java process is PID 1
CMD ["java", "-jar", "app.jar"]

# incorrect — a shell holds PID 1, delaying/blocking SIGTERM delivery
CMD java -jar app.jar
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
```

Using exec form (`["java", "-jar", "..."]`) makes the JVM process itself PID 1, so it receives SIGTERM immediately. Shell form gets wrapped as `/bin/sh -c "..."`, so the shell holds PID 1, and the shell may not forward SIGTERM to the child JVM process, or may delay it. See [graceful-shutdown.md](graceful-shutdown.md) for the detailed shutdown flow.

---

## Never bake environment variables into the image

```dockerfile
# forbidden
ENV DATABASE_PASSWORD=mypassword
COPY .env .env
```

The environment variables that `AwsProperties`, `SesProperties` (→ [config.md](config.md)) bind, and `jwt.secret`, are injected at **container run time**, not image build time.

```bash
docker run --env-file .env.docker myapp
docker run -e AWS_REGION=us-east-1 -e JWT_SECRET=secret myapp
```

On Kubernetes/ECS, this is replaced with a Secret resource or Secrets Manager integration (see [secret-manager.md](secret-manager.md)).

---

## Health check endpoint — Spring Boot Actuator

Spring Boot gets liveness/readiness probes without a separate controller just by adding Actuator. `examples/build.gradle.kts` actually has the `spring-boot-starter-actuator` dependency, and `SecurityConfig` whitelists `/actuator/health/**`.

```kotlin
// build.gradle.kts — actual code
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

```yaml
# application.yml — actual code
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

```
GET /actuator/health/liveness   → 200: the process is alive
GET /actuator/health/readiness  → 200: can receive traffic / 503: shutting down or still initializing
```

See [graceful-shutdown.md](graceful-shutdown.md) for how this ties into the detailed shutdown sequence.

---

## Principles

- **Multi-stage build is required**: `examples/Dockerfile` keeps the Gradle/Kotlin compiler and the entire source only in the build stage, never in the final image.
- **Use a JRE base image**: the runtime stage is `eclipse-temurin:21-jre-alpine`.
- **Exclude `.gradle/`, `build/` via `.dockerignore`**.
- **CMD uses exec form**: `["java", "-jar", "app.jar"]`.
- **Environment variables are injected from outside the image**: never bake values into the `Dockerfile` with `ENV`.
- **Actuator-based liveness/readiness probes**: the Actuator dependency is in `build.gradle.kts`, and `/actuator/health/liveness`·`/actuator/health/readiness` are actually served.
- **Dockerfile `HEALTHCHECK`**: runs `wget -qO- http://localhost:8080/actuator/health/liveness || exit 1` every 10 seconds. Since an orchestrator's (Kubernetes/ECS) liveness/readiness probe plays the same role, this is redundant in such environments, but it's useful for understanding container state in a standalone `docker run` setup.
- The harness's `dockerfile-conventions` rule parses `examples/Dockerfile` as text and mechanically verifies four of the principles above — a multi-stage build (2+ `FROM`s), the presence of `HEALTHCHECK`, the presence of `USER` (non-root execution), and that `.dockerignore` has exclusion patterns for build artifacts (`build/`, `.gradle/`, etc.) and `.git`.

### Related documents

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM handling, health check patterns
- [config.md](config.md) — environment variable management
