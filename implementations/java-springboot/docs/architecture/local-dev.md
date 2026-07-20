# 로컬 개발 환경 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [local-dev.md](../../../../docs/architecture/local-dev.md) 참고.

## 실제 예시 — Postgres + LocalStack(SES/SQS)

`implementations/java-springboot/examples/docker-compose.yml`은 root 원칙을 실제로 따르고 있다:

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

- **버전 고정**: `postgres:16-alpine`, `localstack/localstack:3.0` — root의 "LocalStack 이미지 버전을 고정한다" 원칙 준수.
- **healthcheck가 실제 서비스 API를 호출**: `pg_isready`(Postgres 연결 확인), `awslocal ses list-identities`(SES 서비스 초기화 완료 확인) — 컨테이너 프로세스가 떠 있어도 내부 서비스 준비가 끝나지 않은 상태를 정확히 감지한다.
- **`SERVICES: ses,secretsmanager,sqs`로 필요한 서비스만 활성화**: LocalStack 기동 시간과 메모리를 절약한다. `sqs`는 `OutboxPoller`/`OutboxConsumer`가 쓰는 domain-events 큐용이다([domain-events.md](domain-events.md) 참고).

---

## LocalStack 초기화 스크립트 — SES 발신자 검증 + SQS 큐 생성

```bash
#!/bin/sh
# examples/localstack/init-ses.sh — 실제 코드
set -e
awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

`./localstack:/etc/localstack/init/ready.d`에 마운트되어 LocalStack 기동 완료 시 자동 실행된다. **SES를 사용하는 프로젝트는 발신자 이메일 검증이 필수다** — LocalStack의 SES 에뮬레이터도 실제 SES처럼 미검증 발신자의 발송 요청을 거부하기 때문이다. `NotificationE2ETest`가 `@BeforeAll`에서 별도로 `verifyEmailIdentity`를 한 번 더 호출하는 것은, Testcontainers가 매 테스트 실행마다 새 LocalStack 컨테이너를 띄우므로 `docker-compose.yml`의 초기화 스크립트가 적용되지 않기 때문이다(테스트는 compose 파일을 사용하지 않는다) — 로컬 개발용 compose 설정과 테스트용 Testcontainers 설정은 서로 독립적으로 각자 초기화를 챙겨야 한다.

같은 `ready.d` 디렉토리에 `init-sqs.sh`도 마운트되어, `OutboxPoller`/`OutboxConsumer`가 쓰는 `domain-events` 큐와 그 DLQ(`domain-events-dlq`)를 만든다:

```bash
#!/bin/sh
# examples/localstack/init-sqs.sh — 실제 코드
set -e
awslocal sqs create-queue --queue-name domain-events-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

DLQ를 먼저 만들고 메인 큐에 `RedrivePolicy`로 연결하는 순서다 — `maxReceiveCount=3`을 넘겨 재시도해도 실패하는 메시지는 DLQ로 격리된다([scheduling.md — DLQ 모니터링](scheduling.md#dlq-모니터링) 컨벤션). E2E 테스트(`CardControllerE2ETest` 등)는 Testcontainers `LocalStackContainer`를 쓰는데, 이 컨테이너는 로컬 init 스크립트를 마운트하지 않으므로 `support/SqsTestQueue.java`가 SDK 호출로 동일한 구성을 재현한다.

---

## `app` 서비스와 `.env.*` 파일

`docker-compose.yml`은 `database`/`localstack`뿐 아니라 `app` 서비스도 갖고 있고, `profiles: [app]`로 기본 기동에서는 제외된다 — 기본 `docker compose up -d`는 인프라(DB, LocalStack)만 띄우고, 앱까지 컨테이너로 실행하려면 `docker compose --profile app up -d --build`를 쓴다:

```yaml
# docker-compose.yml — 실제 코드 (일부)
  app:
    build: .
    ports: ['8080:8080']
    env_file:
      - .env.development
    environment:
      # 컨테이너 네트워크 안에서는 localhost 대신 서비스명으로 연결한다.
      SPRING_DATASOURCE_URL: jdbc:postgresql://database:5432/app
      AWS_ENDPOINT_URL: http://localstack:4566
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']
```

`environment:`가 `env_file:`보다 우선 적용되므로, 호스트 직접 실행용 `.env.development`를 그대로 재사용하면서 컨테이너 네트워크에서만 필요한 두 값(`SPRING_DATASOURCE_URL`, `AWS_ENDPOINT_URL`)만 오버라이드한다.

`.env.example`(커밋됨)과 `.env.development`(`.gitignore`에 포함, `.env.example`을 복사해서 만듦)도 이미 있다:

```env
# .env.example — 실제 코드 (호스트에서 ./gradlew bootRun으로 앱을 직접 실행할 때 사용)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/app
SPRING_DATASOURCE_USERNAME=dev
SPRING_DATASOURCE_PASSWORD=dev

AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events

JWT_SECRET=local-dev-secret-local-dev-secret
```

Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로(Node의 `dotenv`에 대응하는 표준 메커니즘이 없다), `export $(cat .env.development | xargs) && ./gradlew bootRun`처럼 셸에 로드하거나 direnv 등을 쓴다. `application.yml`이 `${AWS_ACCESS_KEY_ID:test}` 형태로 기본값을 내장하고 있어, `.env.development` 없이도 로컬 기동은 가능하다 — `.env.development`는 그 기본값을 명시적으로 관리하고 싶을 때 쓴다.

---

## AWS SDK에서 LocalStack 연동

```java
// account/infrastructure/notification/SesConfig.java — 실제 코드
@Bean
public SesClient sesClient(
        @Value("${aws.region:us-east-1}") String region,
        @Value("${aws.endpoint-url:}") String endpointUrl,
        @Value("${aws.access-key-id:test}") String accessKeyId,
        @Value("${aws.secret-access-key:test}") String secretAccessKey
) {
    SesClientBuilder builder = SesClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
    if (endpointUrl != null && !endpointUrl.isBlank()) {
        builder.endpointOverride(URI.create(endpointUrl));
    }
    return builder.build();
}
```

`aws.endpoint-url`이 설정되면 LocalStack으로, 비어 있으면 실제 AWS 엔드포인트로 연결한다 — root의 "환경 변수로 엔드포인트를 분기" 원칙과 정확히 일치한다. `StaticCredentialsProvider`를 항상 명시적으로 사용하는 것도 root의 "로컬/테스트는 자격증명을 고정값으로" 권장과 일치한다 — SDK의 기본 자격증명 탐색 체인(IMDS 등)은 컨테이너/샌드박스 환경에서 실패까지 지연이 크다.

`outbox/SqsConfig.java`의 `sqsClient()` 빈도 동일한 구성(같은 `AwsProperties`, 같은 endpoint 분기)을 따른다 — `OutboxPoller`/`OutboxConsumer`가 이 하나의 `SqsClient`를 공유한다([domain-events.md](domain-events.md) 참고).

---

## 실행 방법

```bash
# 1. 인프라 기동 (Postgres + LocalStack)
cd implementations/java-springboot/examples
docker compose up -d

# 2. 앱 실행 (호스트에서 직접)
./gradlew bootRun

# 로그 확인
docker compose logs -f localstack

# 종료
docker compose down

# 종료 + 데이터 삭제
docker compose down -v
```

---

## 원칙

- **로컬 개발 시 외부 서비스에 직접 연결하지 않는다**: DB는 Docker Compose, SES/SQS는 LocalStack.
- **모든 인프라 서비스에 healthcheck를 설정한다**: `pg_isready`, `awslocal ses list-identities`.
- **초기화 스크립트를 커밋에 포함한다**: `localstack/init-ses.sh`, `localstack/init-sqs.sh`.
- **이미지 버전을 고정한다**: `postgres:16-alpine`, `localstack/localstack:3.0`.
- **profiles로 앱 서비스를 분리한다**: `app` 서비스는 `profiles: [app]`로 기본 기동에서 제외된다.

---

### 관련 문서

- [config.md](config.md) — 환경 변수 검증, 관심사별 설정 파일 분리
- [secret-manager.md](secret-manager.md) — LocalStack Secrets Manager 대체
- [container.md](container.md) — 앱 자체의 컨테이너 이미지, `app` 서비스 추가 시 필요
