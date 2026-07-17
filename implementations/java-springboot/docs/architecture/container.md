# 컨테이너 이미지 (Spring Boot / JVM)

> 프레임워크 무관 원칙은 루트 [container.md](../../../../docs/architecture/container.md) 참고.

## 멀티스테이지 Dockerfile + `HEALTHCHECK`

`examples/Dockerfile`은 아래 "멀티스테이지 빌드 — Layered JAR" 절과 거의 동일한 3-스테이지(Build/Extract/Runtime) 구조다 — Layered JAR 추출, JRE 베이스 런타임(`eclipse-temurin:21-jre-alpine`), non-root `spring` 사용자, `exec form` ENTRYPOINT, `HEALTHCHECK` 지시문을 모두 사용한다. 아래 섹션들은 그 실제 코드를 그대로 보여준다.

`build.gradle`에는 `spring-boot-starter-actuator` 의존성과 liveness/readiness 프로브 설정이 있다([graceful-shutdown.md](graceful-shutdown.md) 참고) — `/actuator/health/liveness`, `/actuator/health/readiness`가 실제로 동작한다. `Dockerfile`의 `HEALTHCHECK` 지시문도 아래 절이 제안하는 방식을 그대로 따른다 — 오케스트레이터(Kubernetes 등)의 liveness/readiness probe가 이 역할을 대신하므로 필수는 아니지만, 단독 `docker run` 환경에서도 상태를 확인할 수 있도록 추가했다.

---

## 멀티스테이지 빌드 — Layered JAR

Spring Boot는 `bootJar`가 생성하는 fat JAR을 레이어별로 분해할 수 있는 기능(`layered jar`)을 기본 제공한다. 의존성 레이어(자주 안 바뀜)와 애플리케이션 코드 레이어(자주 바뀜)를 분리하면 Docker 레이어 캐시 재사용률이 크게 올라간다.

```dockerfile
# Dockerfile — 실제 코드
# ---- Stage 1: Build ----
FROM gradle:8.10-jdk21-alpine AS build

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
RUN java -Djarmode=layertools -jar app.jar extract

# ---- Stage 3: Runtime ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 변경 빈도가 낮은 순 -> 높은 순으로 COPY (캐시 재사용 극대화)
COPY --from=extract /app/dependencies/ ./
COPY --from=extract /app/spring-boot-loader/ ./
COPY --from=extract /app/snapshot-dependencies/ ./
COPY --from=extract /app/application/ ./

EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

| 스테이지 | 내용 | 최종 이미지 포함 |
|---------|------|---------------|
| Build | Gradle, devDependencies, 소스 코드, 테스트 | ✗ |
| Extract | 압축 해제 도구(임시) | ✗ |
| Runtime | JRE(JDK 아님) + 레이어별 추출된 애플리케이션 | ✓ |

**JRE vs JDK**: 런타임 스테이지는 `eclipse-temurin:21-jre-alpine`(JRE만)을 사용한다. 컴파일 도구(`javac`)는 Build 스테이지에서만 필요하다.

**non-root 사용자**: `addgroup`/`adduser`로 전용 사용자를 만들고 `USER spring:spring`으로 전환한다. 컨테이너가 루트 권한 없이 실행되도록 하는 최소 권한 원칙이다.

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

`src/test/`를 빌드 컨텍스트에서 제외해도 Gradle 빌드 자체는 컨테이너 내부에서 `bootJar -x test`로 테스트를 건너뛰므로 문제없다(테스트는 CI 파이프라인의 별도 단계에서 이미 실행됨).

---

## ENTRYPOINT — exec form 필수

```dockerfile
# 올바른 방식 — JVM 프로세스를 PID 1로 직접 실행
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# 잘못된 방식 — shell form은 /bin/sh가 PID 1이 되어 SIGTERM이 sh에서 멈춤
ENTRYPOINT java org.springframework.boot.loader.launch.JarLauncher

# 잘못된 방식 — gradle bootRun으로 감싸면 Gradle 데몬이 중간에 끼어듦
CMD ["./gradlew", "bootRun"]
```

`exec form`(`["java", "..."]`)을 쓰면 JVM이 PID 1이 되어 컨테이너 오케스트레이터가 보내는 SIGTERM을 직접, 즉시 수신한다. → 상세 종료 흐름은 [graceful-shutdown.md](graceful-shutdown.md) 참조.

---

## 환경 변수는 이미지에 포함 금지

```dockerfile
# 금지
ENV AWS_SECRET_ACCESS_KEY=xxxx
COPY .env .env
```

`AwsProperties`/`SesProperties`([config.md](config.md) 참고)가 읽는 환경 변수는 이미지 빌드 시점이 아니라 **컨테이너 실행 시점**에 주입한다.

```bash
docker run --env-file .env.docker myapp
docker run -e AWS_REGION=us-east-1 -e AWS_ACCESS_KEY_ID=... myapp
```

오케스트레이션 환경에서는 Kubernetes Secret, ECS Task Definition의 `secrets`, AWS Parameter Store/Secrets Manager를 사용한다.

---

## 헬스체크 엔드포인트 — Spring Boot Actuator

```groovy
// build.gradle — 실제 코드
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

```yaml
# application.yml — 실제 코드
management:
  endpoint:
    health:
      probes:
        enabled: true                # /actuator/health/liveness, /actuator/health/readiness 활성화
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

```
GET /actuator/health/liveness   → 200: 프로세스 생존
GET /actuator/health/readiness  → 200: 트래픽 수신 가능 / 503: 종료 중 또는 초기화 중
```

Actuator의 `livenessState`/`readinessState`는 Spring의 `AvailabilityChangeEvent`와 연동되어 [graceful-shutdown.md](graceful-shutdown.md)가 설명하는 종료 흐름을 프레임워크가 자동으로 관리한다 — 별도의 `isShuttingDown` 플래그를 직접 구현할 필요가 없다.

```dockerfile
# Dockerfile — 실제 코드. 오케스트레이터의 liveness/readiness probe가 이미 이 역할을 하므로
# Kubernetes/ECS 환경에서는 중복이지만, 단독 docker run 환경에서도 상태를 확인할 수 있도록 추가했다.
# eclipse-temurin:21-jre-alpine은 alpine 기반이라 busybox wget이 기본 포함되어 있다.
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
```

---

## 원칙

- **Layered JAR 멀티스테이지 빌드 필수**: 의존성/애플리케이션 코드 레이어를 분리해 캐시 재사용률을 높인다.
- **JRE 런타임, JDK 아님**: 컴파일 도구는 런타임 이미지에 포함하지 않는다.
- **non-root 사용자로 실행**한다.
- **`.dockerignore` 유지**: `build/`, `.gradle/`, `.env*`, `.git`은 반드시 제외.
- **ENTRYPOINT는 exec form**: `["java", "..."]` — SIGTERM 즉시 수신.
- **환경 변수는 이미지 외부에서 주입**한다.
- **Actuator liveness/readiness 프로브 필수**: `management.health.*.enabled=true`.
- **Dockerfile `HEALTHCHECK` 지시문 적용**: `wget`으로 `/actuator/health/liveness`를 확인한다.

---

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM 처리, Actuator 프로브 연동
- [config.md](config.md) — 환경 변수 관리
