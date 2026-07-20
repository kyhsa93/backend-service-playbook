# 로컬 개발 환경 (Go)

원칙은 루트 [local-dev.md](../../../../docs/architecture/local-dev.md)를 따른다: Docker Compose로 DB를 띄우고, 클라우드 서비스는 LocalStack으로 대체한다. 이 저장소의 Go 예제는 이 패턴을 SES 알림·SQS Outbox 발행/수신까지 포함해 실제로 구현했다 — `implementations/go/examples/docker-compose.yml`과 `localstack/init-ses.sh`/`init-secrets.sh`/`init-sqs.sh`가 그 실물이다. 이 문서는 그 실제 구성을 근거로 설명한다.

---

## 실제 구성 — `implementations/go/examples/docker-compose.yml`

```yaml
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

  app:
    build: .
    ports: ['8080:8080']
    env_file:
      - .env.development
    environment:
      # 컨테이너 네트워크 안에서는 localhost 대신 서비스명으로 연결한다.
      DATABASE_URL: postgres://dev:dev@database:5432/app?sslmode=disable
      AWS_ENDPOINT_URL: http://localstack:4566
      SQS_DOMAIN_EVENT_QUEUE_URL: http://localstack:4566/000000000000/domain-events
    depends_on:
      database:
        condition: service_healthy
      localstack:
        condition: service_healthy
    profiles:
      - app

volumes:
  db-data:
```

root 원칙과 대조하면:
- **LocalStack 이미지 버전 고정** — `localstack/localstack:3.0`(`:latest` 아님). root가 명시한 "최신 태그가 예고 없이 동작을 바꿀 수 있다"는 이유를 그대로 반영.
- **healthcheck가 실제 서비스 API를 호출** — `awslocal ses list-identities`(LocalStack 프로세스가 떠 있어도 SES 서브시스템 초기화가 끝나지 않은 상태를 걸러낸다). `database`도 `pg_isready`로 동일하게 처리.
- **`app` 서비스가 `profiles: [app]`으로 분리되어 있다.** 기본 실행(`docker compose up -d`)은 인프라(database/localstack)만 띄우고, 앱까지 컨테이너로 실행하려면 `docker compose --profile app up -d --build`를 쓴다. `app` 서비스는 컨테이너 네트워크 안에서 `database`/`localstack` 서비스명으로 연결해야 하므로 `DATABASE_URL`/`AWS_ENDPOINT_URL`을 `environment:`로 오버라이드한다(`env_file`보다 우선 적용됨).

---

## LocalStack 초기화 스크립트 — `localstack/init-ses.sh`

```sh
#!/bin/sh
set -e

awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

`docker-compose.yml`의 `volumes: ['./localstack:/etc/localstack/init/ready.d']`가 이 스크립트를 LocalStack 컨테이너 기동 완료 시점에 자동 실행되도록 마운트한다. root 문서가 강조하듯 **SES는 발신자 이메일을 검증하지 않으면 발송 자체를 거부**한다 — LocalStack의 SES 에뮬레이터도 이 동작을 그대로 재현하므로, 이 검증 스크립트가 없으면 `test/notification_e2e_test.go`의 이메일 발송 테스트가 전부 실패한다.

새 AWS 서비스를 추가할 때는 같은 패턴을 따른다: `docker-compose.yml`의 `SERVICES` 목록에 추가하고, `localstack/init-<service>.sh` 스크립트를 새로 만든다. Secrets Manager가 이 패턴으로 추가되어 있다(`SERVICES: ses,secretsmanager,sqs`, `localstack/init-secrets.sh`가 `app/jwt` 시크릿을 생성 — [secret-manager.md](secret-manager.md) 참고). SQS도 같은 패턴이다 — `localstack/init-sqs.sh`:

```sh
#!/bin/sh
set -e

# OutboxPoller가 발행하고 OutboxConsumer가 수신하는 공유 Domain/Integration Event 큐.
# DLQ를 먼저 만들고, 메인 큐에 RedrivePolicy로 연결한다 — maxReceiveCount(3)를 넘겨
# 재시도해도 실패하는 메시지는 DLQ로 격리된다(scheduling.md의 DLQ 컨벤션). nestjs
# 구현체와 동일한 큐 이름·파라미터를 써서 언어 간 일관성을 맞춘다.
awslocal sqs create-queue --queue-name domain-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/domain-events-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

큐 URL(`http://localhost:4566/000000000000/domain-events`)은 `SQS_DOMAIN_EVENT_QUEUE_URL` 환경 변수로 앱에 전달된다(`internal/config/sqs.go`의 `LoadSQSConfig()`). [domain-events.md](domain-events.md)의 Poller/Consumer가 이 큐를 소비한다.

---

## 앱 실행 — 환경 변수로 LocalStack 엔드포인트 분기

Go 코드가 `AWS_ENDPOINT_URL` 환경 변수 유무로 실제 AWS와 LocalStack을 분기하는 방식은 `internal/infrastructure/notification/ses_client.go`에 구현되어 있다:

```go
// internal/infrastructure/notification/ses_client.go
func NewSESClient() *ses.Client {
	region := os.Getenv("AWS_REGION")
	if region == "" {
		region = "us-east-1"
	}
	accessKeyID := os.Getenv("AWS_ACCESS_KEY_ID")
	if accessKeyID == "" {
		accessKeyID = "test"       // LocalStack은 자격증명 값을 검사하지 않음 — 아무 문자열이나 허용
	}
	secretAccessKey := os.Getenv("AWS_SECRET_ACCESS_KEY")
	if secretAccessKey == "" {
		secretAccessKey = "test"
	}

	options := ses.Options{
		Region:      region,
		Credentials: credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
	}
	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		options.BaseEndpoint = &endpoint  // LocalStack: http://localhost:4566
	}
	return ses.New(options)
}
```

기본 자격증명 체인(IMDS 등)을 쓰지 않고 **항상 명시적 정적 자격증명**을 쓰는 이유가 주석에 명시되어 있다 — 자격증명이 없는 샌드박스/CI 환경에서 SDK 기본 체인이 응답을 기다리며 느려지는 것을 피하기 위해서다. root의 "로컬/테스트에서는 고정값(`test`/`test`)을 넣어 IMDS 지연을 피한다" 원칙과 정확히 일치한다. `internal/infrastructure/outbox/sqs_client.go`의 `NewSQSClient()`도 정확히 같은 구성이다.

로컬 실행에 필요한 환경 변수:

```env
DATABASE_URL=postgres://dev:dev@localhost:5432/app?sslmode=disable
AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
JWT_SECRET=local-dev-secret
APP_ENV=development
```

`.env.example`(커밋됨)과 `.env.development`(`.gitignore`에 포함, `.env.example`을 복사해서 만듦)가 있다. Go 표준 라이브러리는 `.env` 파일을 자동으로 읽지 않으므로, `export $(cat .env.development | xargs) && go run ./cmd/server`처럼 셸에 로드하거나 direnv 등을 쓴다.

---

## 실행 방법

```bash
# 1. 인프라 기동 (Postgres + LocalStack)
cd implementations/go/examples
docker compose up -d

# 2. 마이그레이션 실행 (현재 별도 마이그레이션 도구 없음 — psql로 직접 적용하거나 E2E 테스트가 자동 실행)
psql "$DATABASE_URL" -f migrations/0001_init.sql
psql "$DATABASE_URL" -f migrations/0002_add_email_and_sent_emails.sql
psql "$DATABASE_URL" -f migrations/0003_add_outbox.sql

# 3. 앱 실행 (호스트에서 직접)
export $(cat .env.development | xargs) && go run ./cmd/server

# --- 또는 ---

# 전체 컨테이너 기동 (앱 포함)
docker compose --profile app up -d --build

# 로그 확인
docker compose logs -f localstack

# 종료
docker compose down          # 데이터 유지
docker compose down -v       # 데이터까지 삭제
```

E2E 테스트(`test/account_e2e_test.go`)는 이 `docker-compose.yml`에 의존하지 않는다 — testcontainers-go가 매 테스트 실행마다 자체적으로 Postgres/LocalStack(`ses,sqs`) 컨테이너를 새로 기동하고 마이그레이션까지 적용한다([testing.md](testing.md) 참고). `localstack/init-sqs.sh`는 `docker-compose.yml`이 마운트하는 로컬 수동 실행 전용이라 testcontainers-go 컨테이너에는 마운트되지 않으므로, `test/account_e2e_test.go`의 `setupDomainEventQueue`가 SQS API(`CreateQueue`/`GetQueueAttributes`)를 직접 호출해 동일한 큐+DLQ 상태를 만든다. `docker-compose.yml`은 순전히 **로컬 수동 개발**(`go run` + curl/Postman으로 API 호출)을 위한 것이다.

---

### 관련 문서

- [config.md](config.md) — 환경 변수 검증, `.env` 파일 분리 시 참고
- [secret-manager.md](secret-manager.md) — Secrets Manager 로컬 대체(LocalStack `secretsmanager`)
- [domain-events.md](domain-events.md) — Outbox → SQS(Poller)/SQS → Handler(Consumer) 실제 코드
- [container.md](container.md) — 앱 자체를 컨테이너로 실행할 때의 Dockerfile
- [testing.md](testing.md) — testcontainers-go 기반 E2E와의 차이
