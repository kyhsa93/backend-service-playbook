# 로컬 개발 환경 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [local-dev.md](../../../../docs/architecture/local-dev.md) 참고.

## 이미 구현된 실제 예시 — Postgres + LocalStack(SES)

`implementations/java-springboot/examples/docker-compose.yml`은 root 원칙을 이미 실제로 따르고 있다:

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

- **버전 고정**: `postgres:16-alpine`, `localstack/localstack:3.0` — root의 "LocalStack 이미지 버전을 고정한다" 원칙 준수.
- **healthcheck가 실제 서비스 API를 호출**: `pg_isready`(Postgres 연결 확인), `awslocal ses list-identities`(SES 서비스 초기화 완료 확인) — 컨테이너 프로세스가 떠 있어도 내부 서비스 준비가 끝나지 않은 상태를 정확히 감지한다.
- **`SERVICES: ses`로 필요한 서비스만 활성화**: LocalStack 기동 시간과 메모리를 절약한다.

---

## LocalStack 초기화 스크립트 — SES 발신자 검증

```bash
#!/bin/sh
# examples/localstack/init-ses.sh — 실제 코드
set -e
awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
```

`./localstack:/etc/localstack/init/ready.d`에 마운트되어 LocalStack 기동 완료 시 자동 실행된다. **SES를 사용하는 프로젝트는 발신자 이메일 검증이 필수다** — LocalStack의 SES 에뮬레이터도 실제 SES처럼 미검증 발신자의 발송 요청을 거부하기 때문이다. `NotificationE2ETest`가 `@BeforeAll`에서 별도로 `verifyEmailIdentity`를 한 번 더 호출하는 것은, Testcontainers가 매 테스트 실행마다 새 LocalStack 컨테이너를 띄우므로 `docker-compose.yml`의 초기화 스크립트가 적용되지 않기 때문이다(테스트는 compose 파일을 사용하지 않는다) — 로컬 개발용 compose 설정과 테스트용 Testcontainers 설정은 서로 독립적으로 각자 초기화를 챙겨야 한다.

---

## 알려진 gap — `app` 서비스와 `.env.*` 파일 부재

root가 권장하는 두 가지가 이 저장소에는 없다:

**1) `app` 서비스가 compose에 없다** — 현재는 인프라(DB, LocalStack)만 compose로 띄우고 앱은 항상 `./gradlew bootRun`으로 호스트에서 직접 실행하는 것을 전제한다. `--profile app`으로 앱까지 컨테이너 실행하는 옵션이 없다:

```yaml
# docker-compose.yml — 추가 시 (제안)
  app:
    build: .
    ports: ['8080:8080']
    env_file: ['.env.docker']
    depends_on:
      database: { condition: service_healthy }
      localstack: { condition: service_healthy }
    profiles: ['app']
```

`Dockerfile` 자체가 아직 없으므로([container.md](container.md) 참고) 이 서비스는 Dockerfile 작성과 함께 추가해야 한다.

**2) `.env.development`/`.env.docker` 파일이 없다** — 현재 로컬 개발자는 환경 변수를 셸에서 직접 export하거나 IDE 실행 설정에 하드코딩해야 한다.

```env
# .env.development — 추가 시 (제안, 호스트에서 앱 직접 실행)
AWS_REGION=us-east-1
AWS_ENDPOINT_URL=http://localhost:4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
SES_SENDER_EMAIL=no-reply@backend-service-playbook.example.com
```

```env
# .env.docker — 추가 시 (앱이 컨테이너 안에서 실행될 때 — 서비스명으로 연결)
AWS_ENDPOINT_URL=http://localstack:4566
```

Spring Boot는 `.env` 파일을 기본 지원하지 않으므로(Node의 `dotenv`에 대응하는 표준 메커니즘이 없다), `.env.development`는 `direnv`나 IDE의 "Run Configuration" 환경 변수 가져오기로 로드하거나, `spring-dotenv` 같은 서드파티 라이브러리를 추가해야 한다. 가장 Spring다운 대안은 `application-local.yml` 프로필 파일에 로컬 기본값을 직접 기술하는 것이다([config.md](config.md) 참고) — 이 저장소의 `application.yml`이 이미 `${AWS_ACCESS_KEY_ID:test}` 형태로 기본값을 내장하는 것이 이 전략에 해당한다.

---

## AWS SDK에서 LocalStack 연동 — 이미 구현됨

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

- **로컬 개발 시 외부 서비스에 직접 연결하지 않는다**: DB는 Docker Compose, SES는 LocalStack — 이미 준수.
- **모든 인프라 서비스에 healthcheck를 설정한다**: 이미 준수 (`pg_isready`, `awslocal ses list-identities`).
- **초기화 스크립트를 커밋에 포함한다**: `localstack/init-ses.sh` — 이미 준수.
- **이미지 버전을 고정한다**: `postgres:16-alpine`, `localstack/localstack:3.0` — 이미 준수.
- **개선 여지**: `app` 서비스를 `profiles: [app]`으로 추가하고, `.env.development`/`.env.docker` 또는 `application-local.yml`로 환경 변수 관리를 체계화한다.

---

### 관련 문서

- [config.md](config.md) — 환경 변수 검증, 관심사별 설정 파일 분리
- [secret-manager.md](secret-manager.md) — LocalStack Secrets Manager 대체
- [container.md](container.md) — 앱 자체의 컨테이너 이미지, `app` 서비스 추가 시 필요
