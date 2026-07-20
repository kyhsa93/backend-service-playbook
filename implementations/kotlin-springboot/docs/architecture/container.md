# 컨테이너 이미지 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root container.md](../../../../docs/architecture/container.md) 참조.

## Dockerfile + Actuator 헬스체크

`implementations/kotlin-springboot/examples/Dockerfile`은 아래 멀티스테이지 빌드를 실제 코드 그대로 구현한다(Gradle Kotlin DSL 빌드, Kotlin 1.9.25 + Spring Boot 3.3.5 + Java 21 toolchain). `.dockerignore`도 아래 내용 그대로 존재한다. Actuator 기반 liveness/readiness 프로브는 `build.gradle.kts`의 `spring-boot-starter-actuator` 의존성과 `application.yml`의 `management.*` 설정으로 구성되어 있다(아래 "헬스체크 엔드포인트" 절 참고).

---

## 멀티스테이지 빌드

Kotlin/JVM 빌드는 Gradle 실행 자체가 무겁기 때문에(데몬, 의존성 캐시), **빌드 스테이지와 런타임 스테이지를 분리해 최종 이미지에는 JDK 전체가 아닌 JRE + 빌드 산출물(JAR)만** 남긴다.

```dockerfile
# examples/Dockerfile — 실제 코드
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
```

**각 스테이지의 목적:**

| 스테이지 | 내용 | 최종 이미지 포함 |
|---------|------|---------------|
| Build | Gradle + Kotlin 컴파일러 + 전체 소스 + 의존성 캐시 | ✗ |
| Runtime | JRE(JDK 아님) + 컴파일된 `app.jar` | ✓ |

JDK(개발용 도구 포함) 대신 **JRE(실행 전용)** 베이스 이미지를 쓰는 것은 Java/Kotlin 생태계 고유의 최적화다 — Node.js에는 이런 구분이 없다.

---

## .dockerignore

```
# examples/.dockerignore — 실제 코드
.gradle/
build/
*.jar
.git
.env*
docker-compose.yml
*.log
```

`.gradle/`(로컬 캐시), `build/`(이전 빌드 산출물)를 빌드 컨텍스트에서 제외해 컨텍스트 전송 속도와 캐시 신뢰성을 높인다. 컨테이너 내부에서 `gradle bootJar`가 다시 실행되므로 호스트의 `build/` 산출물은 불필요하다.

---

## CMD — exec form으로 JVM을 PID 1로 직접 실행

```dockerfile
# 올바른 방식 — java 프로세스가 PID 1
CMD ["java", "-jar", "app.jar"]

# 잘못된 방식 — 셸이 PID 1을 가져 SIGTERM 전달이 지연/차단됨
CMD java -jar app.jar
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
```

exec form(`["java", "-jar", "..."]`)을 쓰면 JVM 프로세스가 직접 PID 1이 되어 SIGTERM을 즉시 수신한다. shell form은 `/bin/sh -c "..."`로 감싸져 셸이 PID 1을 갖고, 셸이 SIGTERM을 자식 JVM 프로세스로 전달하지 않거나 지연시킬 수 있다. 상세 종료 흐름은 [graceful-shutdown.md](graceful-shutdown.md) 참조.

---

## 환경 변수는 이미지에 포함 금지

```dockerfile
# 금지
ENV DATABASE_PASSWORD=mypassword
COPY .env .env
```

`AwsProperties`, `SesProperties`(→ [config.md](config.md))가 바인딩할 환경 변수와 `jwt.secret`은 이미지 빌드 시점이 아니라 **컨테이너 실행 시점**에 주입한다.

```bash
docker run --env-file .env.docker myapp
docker run -e AWS_REGION=us-east-1 -e JWT_SECRET=secret myapp
```

Kubernetes/ECS에서는 Secret 리소스나 Secrets Manager 연동으로 대체한다 ([secret-manager.md](secret-manager.md) 참조).

---

## 헬스체크 엔드포인트 — Spring Boot Actuator

Spring Boot는 Actuator만 추가하면 liveness/readiness 프로브를 별도 컨트롤러 없이 얻는다. `examples/build.gradle.kts`에 `spring-boot-starter-actuator` 의존성이 실제로 있고, `SecurityConfig`는 `/actuator/health/**`를 화이트리스트로 열어둔다.

```kotlin
// build.gradle.kts — 실제 코드
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

```yaml
# application.yml — 실제 코드
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
GET /actuator/health/liveness   → 200: 프로세스 생존
GET /actuator/health/readiness  → 200: 트래픽 수신 가능 / 503: 종료 중 또는 초기화 중
```

상세 종료 순서와의 연동은 [graceful-shutdown.md](graceful-shutdown.md) 참조.

---

## 원칙

- **멀티스테이지 빌드 필수**: `examples/Dockerfile`이 Gradle/Kotlin 컴파일러와 전체 소스를 빌드 스테이지에만 두고 최종 이미지에는 포함하지 않는다.
- **JRE 베이스 이미지 사용**: 런타임 스테이지는 `eclipse-temurin:21-jre-alpine`.
- **.dockerignore로 `.gradle/`, `build/` 제외**.
- **CMD는 exec form**: `["java", "-jar", "app.jar"]`.
- **환경 변수는 이미지 외부에서 주입**: `Dockerfile`에 `ENV`로 값을 굽지 않는다.
- **Actuator 기반 liveness/readiness 프로브**: `build.gradle.kts`에 Actuator 의존성 있음, `/actuator/health/liveness`·`/actuator/health/readiness`가 실제로 서빙된다.
- **Dockerfile `HEALTHCHECK`**: `wget -qO- http://localhost:8080/actuator/health/liveness || exit 1`을 10초 간격으로 실행한다. 오케스트레이터(Kubernetes/ECS)의 liveness/readiness probe가 같은 역할을 하므로 그런 환경에서는 중복이지만, 단독 `docker run` 환경에서 컨테이너 상태를 파악하는 데 유용하다.
- harness `dockerfile-conventions` 규칙이 `examples/Dockerfile`을 텍스트로 파싱해 위 원칙 중 기계적으로 검증 가능한 세 가지 — 멀티스테이지 빌드(`FROM` 2개 이상), `HEALTHCHECK` 존재, `.dockerignore`의 빌드 산출물(`build/`, `.gradle/` 등)/`.git` 제외 패턴 존재 — 를 확인한다.

### 관련 문서

- [graceful-shutdown.md](graceful-shutdown.md) — SIGTERM 처리, 헬스체크 패턴
- [config.md](config.md) — 환경 변수 관리
