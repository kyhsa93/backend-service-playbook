# 공유 모듈 구조

도메인에 속하지 않는 공유 코드는 아래 경로에 배치한다 — 실제 코드 기준:

```
src/
  common/                          # 프로젝트 공통 유틸
    application/service/
      secret-service.ts            # SecretService abstract class
    infrastructure/
      secret-service-impl.ts       # Secrets Manager 구현체 (secret-manager.md)
    correlation-id-store.ts        # AsyncLocalStorage 기반 저장소
    correlation-id.middleware.ts   # Correlation ID 주입 미들웨어
    generate-error-response.ts
    generate-id.ts
    logging.interceptor.ts         # 요청 로깅 인터셉터
  config/                          # 관심사별 설정 (config.md)
    app.config.ts
    aws.config.ts
    database.config.ts
    jwt.config.ts
    notification.config.ts
    validation.config.ts
  database/                        # 데이터베이스 공유 코드
    data-source.ts                 # TypeORM DataSource (CLI 마이그레이션과 공유)
    transaction-manager.ts
    migrations/
  outbox/                          # Outbox 모듈 (@Global)
    outbox-module.ts
    outbox.entity.ts
    outbox-writer.ts
    event-handler-registry.ts
  auth/                            # 인증 모듈 (공유)
    auth-module.ts
    auth-service.ts                # 토큰 발급/검증 (JWT)
    auth.guard.ts                  # Bearer 토큰 추출 Guard
    interface/
      auth-controller.ts           # POST /auth/sign-in 등
      dto/
  <domain>/                        # 도메인 모듈
    ...
```

- `src/common/` — 에러 처리, 인터셉터, Correlation ID, Secrets Manager 등 프레임워크 공통 코드. **DatabaseModule 같은 별도 `@Global` 모듈이나 `BaseEntity` 상속 클래스는 없다** — `AccountEntity`/`OutboxEntity`가 각자 컬럼을 인라인 선언한다.
- `src/config/` — 관심사별 설정 팩토리/헬퍼 함수([config.md](config.md) 참고)
- `src/database/` — TypeORM `DataSource`, `TransactionManager`. `AppDataSource`는 CLI 마이그레이션과 앱이 공유한다([persistence.md](persistence.md) 참고)
- `src/outbox/` — `OutboxWriter`, `EventHandlerRegistry`, `OutboxPoller`, `OutboxConsumer`, `SqsClientProvider`. **모든 도메인(Account/Card/Payment 등)이 이 하나의 공유 모듈만 쓴다** — 도메인별 `OutboxRelay`는 없다. `OutboxPoller`가 `outbox` 테이블을 폴링해 SQS로 발행하고, `OutboxConsumer`가 SQS를 long-poll로 소비해 `EventHandlerRegistry`로 라우팅한다. 각 도메인 모듈은 `onModuleInit()`에서 자기 Domain/Integration Event 핸들러를 이 레지스트리 하나에 등록한다([domain-events.md](domain-events.md) 참고).
- `src/auth/` — 인증/인가 공유 모듈. 별도 에러 메시지 enum(`auth-error-message.ts`) 없이 `UnauthorizedException()`을 직접 던진다.

> **notification은 여기 없다.** SES 이메일 발송(`NotificationService`)은 `AccountModule`만 사용하는 Account 전용 Technical Service이므로 `src/account/application/service/notification-service.ts`(인터페이스) + `src/account/infrastructure/notification/`(구현체·Entity)로 도메인 내부에 둔다 — [domain-service.md](../../../../docs/architecture/domain-service.md)의 Technical Service 배치 원칙 참고. 이후 다른 도메인(Card 등)도 알림이 필요해지면 그때 공유 모듈로의 승격 여부를 다시 판단한다(YAGNI) — 지금 미리 공유 위치로 빼지 않는다.
