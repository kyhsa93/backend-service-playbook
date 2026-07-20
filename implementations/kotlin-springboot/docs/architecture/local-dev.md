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
      SERVICES: ses,secretsmanager,sqs
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

Postgres + LocalStack(SES/Secrets Manager/SQS) 두 인프라 서비스 모두 `healthcheck`가 설정되어 있다 — root의 "모든 인프라 서비스에 healthcheck 필수" 원칙을 그대로 따른다. `awslocal ses list-identities`처럼 실제 서비스 API를 호출하는 형태(단순 프로세스 생존 확인이 아니라)인 것도 root 권장과 일치한다.

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

## LocalStack SQS 초기화 — `init-sqs.sh`

```bash
# examples/localstack/init-sqs.sh — 실제 코드
#!/bin/sh
set -e
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

`OutboxPoller`가 발행하고 `OutboxConsumer`가 수신하는 공유 Domain/Integration Event 큐다. DLQ를
먼저 만들고 메인 큐에 `RedrivePolicy`로 연결한다 — `maxReceiveCount(3)`를 넘겨 재시도해도 실패하는
메시지는 DLQ로 격리된다([domain-events.md](domain-events.md), [scheduling.md](scheduling.md)의
DLQ 컨벤션 참고).

---

## `app` 서비스와 `.env.*` 파일

`docker-compose.yml`은 `database`/`localstack`뿐 아니라 `app` 서비스도 갖고 있고, `profiles: [app]`로 기본 기동에서는 제외된다 — 기본 `docker compose up -d`는 인프라(Postgres, LocalStack)만 띄우고, 앱까지 컨테이너로 실행하려면 `docker compose --profile app up -d --build`를 쓴다:

```yaml
# docker-compose.yml — 실제 코드
  app:
    build: .
    ports: ['8080:8080']
    env_file:
      - .env.development
    environment:
      # 컨테이너 네트워크 안에서는 localhost 대신 서비스명으로 연결한다.
      SPRING_DATASOURCE_URL: jdbc:postgresql://database:5432/app
      AWS_ENDPOINT_URL: http://localstack:4566
      SQS_DOMAIN_EVENT_QUEUE_URL: http://localstack:4566/000000000000/domain-events
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']
```

`.env.example`(커밋됨)과 `.env.development`(`.gitignore`에 포함, `.env.example`을 복사해서 만듦)도 이미 있다 — `SPRING_DATASOURCE_URL`/`AWS_REGION`/`AWS_ENDPOINT_URL`/`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`SES_SENDER_EMAIL`/`JWT_SECRET`을 담는다. Gradle/Kotlin 빌드가 무거워(컴파일 + 데몬) 호스트에서 `./gradlew bootRun` + IDE 증분 컴파일/핫 리로드(Spring DevTools)로 개발하는 것이 기본 흐름이고, `app` 서비스는 배포 환경과 동일한 컨테이너로 검증하고 싶을 때 쓴다.

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
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/app
SPRING_DATASOURCE_USERNAME=dev
SPRING_DATASOURCE_PASSWORD=dev
```

Spring Boot는 `SPRING_DATASOURCE_URL` 같은 대문자 스네이크 케이스 환경 변수를 `spring.datasource.url` 프로퍼티로 **자동 relaxed binding**한다 — Node/NestJS의 `ConfigModule`처럼 별도 매핑 코드가 필요 없다. 관심사별 `@ConfigurationProperties` data class로 이 값들을 타입 세이프하게 묶는 방법은 [config.md](config.md) 참조.

`AWS_ENDPOINT_URL`이 설정되어 있으면 LocalStack, 비어 있으면 실제 AWS로 분기하는 것은 `SesConfig.sesClient()`가 구현하고 있다 — [config.md](config.md), [secret-manager.md](secret-manager.md) 참조.

---

## 원칙

- **모든 인프라 서비스에 실제 서비스 API 기반 healthcheck**: `pg_isready`, `awslocal ses list-identities`.
- **LocalStack 초기화 스크립트는 커밋에 포함**: `localstack/init-ses.sh`/`init-secrets.sh`/`init-sqs.sh` — 모든 개발자가 같은 환경을 재현한다.
- **LocalStack 이미지 버전 고정**: `localstack/localstack:3.0` — `latest` 태그 사용 금지.
- **SES는 발신자 검증이 필수**: 초기화 스크립트에서 `verify-email-identity`를 반드시 실행한다.
- **앱은 호스트에서 직접 실행이 기본**: Gradle/Kotlin 빌드 특성상 컨테이너 재빌드보다 IDE 증분 컴파일이 개발 루프에 더 적합하다.

### 관련 문서

- [config.md](config.md) — 환경 변수 바인딩, `@ConfigurationProperties`
- [secret-manager.md](secret-manager.md) — Secrets Manager 로컬 대체
- [container.md](container.md) — 앱 자체의 컨테이너 이미지
