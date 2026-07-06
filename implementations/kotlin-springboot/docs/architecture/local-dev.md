# 로컬 개발 환경 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root local-dev.md](../../../../docs/architecture/local-dev.md) 참조.

## 실제 구성 — `examples/docker-compose.yml`

```yaml
# examples/docker-compose.yml — 실제 코드
services:
  database:
    image: postgres:16-alpine
    ports: ['5432:5432']
    environment:
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: dev
      POSTGRES_DB: app
    volumes: ['db-data:/var/lib/postgresql/data']
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U dev -d app']
      interval: 5s
      timeout: 3s
      retries: 5

  localstack:
    image: localstack/localstack:3.0
    ports: ['4566:4566']
    environment:
      SERVICES: ses
      DEFAULT_REGION: us-east-1
    volumes: ['./localstack:/etc/localstack/init/ready.d']
    healthcheck:
      test: ['CMD-SHELL', 'awslocal ses list-identities']
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  db-data:
```

Postgres + LocalStack(SES) 두 인프라 서비스 모두 `healthcheck`가 설정되어 있다 — root의 "모든 인프라 서비스에 healthcheck 필수" 원칙을 그대로 따른다. `awslocal ses list-identities`처럼 실제 서비스 API를 호출하는 형태(단순 프로세스 생존 확인이 아니라)인 것도 root 권장과 일치한다.

---

## LocalStack SES 초기화 — `init-ses.sh`

```bash
# examples/localstack/init-ses.sh — 실제 코드
#!/bin/sh
set -e
awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

`./localstack:/etc/localstack/init/ready.d`로 마운트되어 LocalStack 기동 완료 시 자동 실행된다. SES는 **발신자 이메일 검증이 필수**라는 점이 root 문서에서 특히 강조하는 부분이다 — LocalStack의 SES 에뮬레이터도 실제 SES처럼 미검증 발신자의 발송을 거부하므로, 이 스크립트가 없으면 `NotificationServiceImpl.sendEmail()`이 로컬에서 실패한다. `AccountControllerE2ETest`의 `@BeforeAll verifySesSender()`가 테스트 환경에서 동일한 검증을 Testcontainers 방식으로 반복하는 것도 같은 이유다.

---

## 알려진 차이 — `app` 서비스와 `profiles: [app]`이 compose에 없다

root는 앱 자체도 `profiles: [app]`으로 선택 실행하는 서비스로 포함할 것을 권장하지만, 이 저장소의 `docker-compose.yml`에는 `app` 서비스가 없다. 로컬 개발은 인프라(Postgres, LocalStack)만 컨테이너로 띄우고 앱은 항상 호스트에서 `./gradlew bootRun`으로 직접 실행하는 것이 전제다 — Gradle/Kotlin 빌드가 무거워(컴파일 + 데몬) 매번 컨테이너 이미지를 재빌드하며 개발하기보다 IDE의 증분 컴파일과 핫 리로드(Spring DevTools)를 활용하는 것이 JVM 생태계의 일반적인 개발 흐름이기 때문이다. 앱까지 컨테이너로 실행하고 싶다면 [container.md](container.md)의 Dockerfile을 사용해 `app` 서비스를 추가할 수 있다.

```yaml
# 추가하려면 — container.md의 Dockerfile 기준
  app:
    build: .
    ports: ['8080:8080']
    environment:
      DATABASE_HOST: database
      AWS_ENDPOINT_URL: http://localstack:4566
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']
```

---

## 실행 방법

```bash
cd implementations/kotlin-springboot/examples

# 1. 인프라 기동 (Postgres + LocalStack)
docker compose up -d

# 2. 앱 실행 — 호스트에서 직접 (Gradle Wrapper)
./gradlew bootRun

# 로그 확인
docker compose logs -f localstack

# 종료
docker compose down

# 종료 + 볼륨 삭제 (DB 데이터 초기화)
docker compose down -v
```

---

## 앱 환경 변수 — `application.yml` + 환경 변수 오버라이드

현재 `examples/src/main/resources/application.yml`은 아래처럼 최소 구성이다.

```yaml
spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

`NotificationServiceImpl`/`SesConfig`는 `@Value("\${SES_SENDER_EMAIL:...}")`, `@Value("\${AWS_REGION:us-east-1}")` 같은 개별 `@Value` 주입으로 환경 변수를 읽는다. 로컬에서 `./gradlew bootRun` 실행 시 필요한 환경 변수:

```bash
# .env.development (직접 실행 시 IDE Run Configuration 또는 export로 주입)
AWS_ENDPOINT_URL=http://localhost:4566
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/app
SPRING_DATASOURCE_USERNAME=dev
SPRING_DATASOURCE_PASSWORD=dev
```

Spring Boot는 `SPRING_DATASOURCE_URL` 같은 대문자 스네이크 케이스 환경 변수를 `spring.datasource.url` 프로퍼티로 **자동 relaxed binding**한다 — Node/NestJS의 `ConfigModule`처럼 별도 매핑 코드가 필요 없다. 관심사별 `@ConfigurationProperties` data class로 이 값들을 타입 세이프하게 묶는 방법은 [config.md](config.md) 참조.

`AWS_ENDPOINT_URL`이 설정되어 있으면 LocalStack, 비어 있으면 실제 AWS로 분기하는 것은 `SesConfig.sesClient()`가 이미 구현하고 있다 — [config.md](config.md), [secret-manager.md](secret-manager.md) 참조.

---

## 원칙

- **모든 인프라 서비스에 실제 서비스 API 기반 healthcheck**: `pg_isready`, `awslocal ses list-identities`.
- **LocalStack 초기화 스크립트는 커밋에 포함**: `localstack/init-ses.sh` — 모든 개발자가 같은 환경을 재현한다.
- **LocalStack 이미지 버전 고정**: `localstack/localstack:3.0` — `latest` 태그 사용 금지.
- **SES는 발신자 검증이 필수**: 초기화 스크립트에서 `verify-email-identity`를 반드시 실행한다.
- **앱은 호스트에서 직접 실행이 기본**: Gradle/Kotlin 빌드 특성상 컨테이너 재빌드보다 IDE 증분 컴파일이 개발 루프에 더 적합하다.

### 관련 문서

- [config.md](config.md) — 환경 변수 바인딩, `@ConfigurationProperties`
- [secret-manager.md](secret-manager.md) — Secrets Manager 로컬 대체
- [container.md](container.md) — 앱 자체의 컨테이너 이미지
