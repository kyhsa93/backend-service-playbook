# Container Image (Spring Boot / JVM)

> For the framework-agnostic principles, see the root [container.md](../../../../docs/architecture/container.md).

## Multi-stage Dockerfile + `HEALTHCHECK`

`examples/Dockerfile` has an almost identical 3-stage (Build/Extract/Runtime) structure to the "Multi-stage build — Layered JAR" section below — it uses Layered JAR extraction, a JRE-base runtime (`eclipse-temurin:21-jre-alpine`), a non-root `spring` user, an `exec form` ENTRYPOINT, and a `HEALTHCHECK` directive. The sections below show that actual code as-is.

`build.gradle` has the `spring-boot-starter-actuator` dependency and liveness/readiness probe configuration (see [graceful-shutdown.md](graceful-shutdown.md)) — `/actuator/health/liveness` and `/actuator/health/readiness` genuinely work. The `Dockerfile`'s `HEALTHCHECK` directive also follows the approach the section below proposes — it isn't strictly necessary since an orchestrator's (Kubernetes, etc.) liveness/readiness probes already play this role, but it's added so status can still be checked in a standalone `docker run` environment.

---

## Multi-stage build — Layered JAR

Spring Boot ships with built-in support for decomposing the fat JAR produced by `bootJar` into layers (`layered jar`). Separating the dependency layer (rarely changes) from the application-code layer (changes often) greatly improves Docker layer-cache reuse.

```dockerfile
# Dockerfile — actual code
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
# Spring Boot 3.3+ replaced the layertools jarmode with the tools jarmode (Boot 4 removes
# layertools entirely) — passing --layers produces the same 4-directory layer structure under --destination.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- Stage 3: Runtime ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# COPY from lowest change frequency to highest (maximizes cache reuse)
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

| Stage | Contents | Included in final image |
|---------|------|---------------|
| Build | Gradle, dev dependencies, source code, tests | No |
| Extract | Extraction tooling (temporary) | No |
| Runtime | JRE (not JDK) + layer-extracted application | Yes |

**JRE vs. JDK**: the runtime stage uses `eclipse-temurin:21-jre-alpine` (JRE only). Compile tooling (`javac`) is only needed in the Build stage.

**Non-root user**: a dedicated user is created with `addgroup`/`adduser` and switched to via `USER spring:spring`. This follows the principle of least privilege so the container never runs with root permissions.

---

## `.dockerignore`

```
build/
.gradle/
*.log
.git
.env*
docker-compose.yml
Dockerfile
src/test/
```

Excluding `src/test/` from the build context is fine, since the Gradle build itself skips tests with `bootJar -x test` inside the container (tests already run in a separate stage of the CI pipeline).

---

## ENTRYPOINT — exec form is required

```dockerfile
# Correct — runs the JVM process directly as PID 1
ENTRYPOINT ["java", "-jar", "app.jar"]

# Incorrect — shell form makes /bin/sh PID 1, so SIGTERM stops at sh
ENTRYPOINT java -jar app.jar

# Incorrect — wrapping with gradle bootRun inserts the Gradle daemon in between
CMD ["./gradlew", "bootRun"]
```

Using `exec form` (`["java", "..."]`) makes the JVM PID 1, so it receives the SIGTERM sent by a container orchestrator directly and immediately. → See [graceful-shutdown.md](graceful-shutdown.md) for the detailed shutdown flow.

---

## Environment variables must never be baked into the image

```dockerfile
# Forbidden
ENV AWS_SECRET_ACCESS_KEY=xxxx
COPY .env .env
```

The environment variables read by `AwsProperties`/`SesProperties` (see [config.md](config.md)) are injected at **container run time**, not at image build time.

```bash
docker run --env-file .env.docker myapp
docker run -e AWS_REGION=us-east-1 -e AWS_ACCESS_KEY_ID=... myapp
```

In an orchestration environment, use Kubernetes Secrets, the ECS Task Definition's `secrets` field, or AWS Parameter Store/Secrets Manager.

---

## Health check endpoint — Spring Boot Actuator

```groovy
// build.gradle — actual code
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

```yaml
# application.yml — actual code
management:
  endpoint:
    health:
      probes:
        enabled: true                # enables /actuator/health/liveness, /actuator/health/readiness
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

```
GET /actuator/health/liveness   → 200: process alive
GET /actuator/health/readiness  → 200: ready to accept traffic / 503: shutting down or initializing
```

Actuator's `livenessState`/`readinessState` are integrated with Spring's `AvailabilityChangeEvent`, so the framework automatically manages the shutdown flow described in [graceful-shutdown.md](graceful-shutdown.md) — there's no need to implement a separate `isShuttingDown` flag by hand.

```dockerfile
# Dockerfile — actual code. Since an orchestrator's (Kubernetes, etc.) liveness/readiness probe
# already plays this role, it's redundant in a Kubernetes/ECS environment, but it's added so
# status can still be checked in a standalone docker run environment.
# eclipse-temurin:21-jre-alpine is Alpine-based, so busybox wget is included by default.
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
```

---

## Principles

- **A layered-JAR multi-stage build is required**: separate the dependency/application-code layers to improve cache-reuse rate.
- **JRE runtime, not JDK**: never include compile tooling in the runtime image.
- **Run as a non-root user.**
- **Maintain `.dockerignore`**: always exclude `build/`, `.gradle/`, `.env*`, `.git`.
- **ENTRYPOINT must use exec form**: `["java", "..."]` — for immediate SIGTERM receipt.
- **Inject environment variables from outside the image.**
- **Actuator liveness/readiness probes are required**: `management.health.*.enabled=true`.
- **Apply the Dockerfile `HEALTHCHECK` directive**: check `/actuator/health/liveness` with `wget`.

---

## Harness verification

`harness/src/rules/DockerfileConventions.java` (rule: `dockerfile-conventions`) checks `Dockerfile`/`.dockerignore` (plain text — the only rule that doesn't check a `.java` file) — confirming there are 2+ `FROM` lines (multi-stage), a `HEALTHCHECK` directive is present, a `USER` directive is present (non-root execution), and `.dockerignore` exists and excludes `.git` and build artifacts (`build`/`.gradle`/`target`).

---

### Related documents

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM handling, Actuator probe integration
- [config.md](config.md) — environment variable management
