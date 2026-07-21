# 디렉토리 구조

```
src/
  common/                              # 공용 유틸
    is-unique-violation.ts             # Postgres unique_violation(23505) 판별
  database/                            # 데이터베이스 공유 코드 (실제 코드에는 별도 @Global 모듈이나 BaseEntity가 없다)
    data-source.ts                     # TypeORM DataSource 설정 — CLI 마이그레이션과 공유
    transaction-manager.ts             # 트랜잭션 매니저 (AsyncLocalStorage 기반)
  outbox/                              # Outbox 공유 코드 (@Global OutboxModule) — 모든 도메인이 공유하는 유일한 경로
    outbox-module.ts
    outbox.entity.ts                   # Outbox 테이블 Entity
    outbox-writer.ts                   # 트랜잭션 안에서 이벤트 저장 (Repository에서 호출)
    outbox-poller.ts                   # Outbox → SQS 발행 (@Interval(1000))
    outbox-consumer.ts                 # SQS → EventHandlerRegistry 라우팅 (long polling)
    sqs-client-provider.ts             # SQSClient 생성
    event-handler-registry.ts          # eventType → Handler 라우팅
    # 도메인별 OutboxRelay는 없다 — 이 OutboxPoller/OutboxConsumer 하나로 통합되어
    # 모든 도메인의 이벤트가 SQS를 경유해 비동기로 처리된다(domain-events.md 참고).
  task-queue/                          # Task Queue 모듈 (공용)
    task-queue-module.ts
    task-queue.ts                      # 인터페이스 (abstract class)
    task-queue-outbox.ts               # Outbox 기반 구현체 (task_outbox에 write)
    task-outbox.entity.ts              # task_outbox 테이블 Entity
    task-outbox-relay.ts               # task_outbox → SQS 발행 (Cron)
    task-execution-log.ts              # TaskExecutionLog 인터페이스 (abstract class)
    task-execution-log-db.ts           # DB 기반 구현체
    task-execution-log.entity.ts       # task_execution_log 테이블 Entity (멱등성 ledger)
    task-execution-log-cleaner.ts      # ledger cleanup (Cron)
    task-consumer.decorator.ts         # @TaskConsumer 데코레이터 (heartbeat 옵션 포함)
    task-consumer-registry.ts          # taskType → Handler 라우팅
    task-queue-consumer.ts             # SQS → Task Controller 디스패치 (폴링)
  config/
    <concern>.config.ts              # 관심사별 설정 팩토리 (database, jwt 등)
    validation.config.ts             # 환경 변수 검증 (harness의 *.config.ts 네이밍 규칙을 따름)
  <domain>/
    domain/                          # 도메인 레이어
      <aggregate-root>.ts
      <entity>.ts
      <value-object>.ts
      <domain-event>.ts
      <aggregate>-repository.ts      # Repository 인터페이스 (abstract class)
    application/
      adapter/
        <external-domain>-adapter.ts    # 외부 도메인 호출 인터페이스 (abstract class)
      service/
        <concern>-service.ts            # 기술 인프라 인터페이스 (abstract class)
      command/
        <domain>-command-service.ts     # Command Service (쓰기 — Repository 사용)
        <verb>-<noun>-command.ts
      query/
        <domain>-query-service.ts       # Query Service (읽기 — Query 인터페이스 사용)
        <domain>-query.ts               # Query 인터페이스 (abstract class)
        <verb>-<noun>-query.ts
        <verb>-<noun>-result.ts
      event/
        <domain>-event-handler.ts       # Domain Event 핸들러 — 도메인 모듈의 onModuleInit()에서
                                         # 공유 outbox/event-handler-registry.ts에 등록한다.
                                         # 도메인별 outbox-relay.ts는 없다 — domain-events.md 참고
    interface/
      <domain>-controller.ts              # HTTP Controller
      <domain>-task-controller.ts         # Task Controller (@TaskConsumer 메서드 보유)
      dto/
        <verb>-<noun>-request-body.ts     # 요청 DTO
        <verb>-<noun>-request-param.ts
        <verb>-<noun>-request-querystring.ts
        <verb>-<noun>-response-body.ts    # 응답 DTO
    infrastructure/
      entity/
        <entity>.entity.ts               # TypeORM Entity
      <aggregate>-repository-impl.ts    # Repository 구현체
      <domain>-query-impl.ts            # Query 구현체 (읽기 전용 DB 접근)
      <external-domain>-adapter-impl.ts # 외부 도메인 Adapter 구현체
      <concern>-service-impl.ts         # 기술 인프라 Service 구현체
      <concern>-scheduler.ts            # Scheduler (@Cron → TaskQueue.enqueue)
    <domain>-module.ts
    <domain>-error-message.ts
    <domain>-enum.ts
    <domain>-constant.ts
```

Technical Service 구현이 구현체 하나로 끝나지 않고 클라이언트 provider·전용 Entity 등 지원 파일이 여러 개 필요하면, `infrastructure/<concern>/` 하위에 묶는다 — `entity/`와 같은 방식이다. 예: Account의 SES 이메일 발송(`NotificationService`, [domain-service.md](../../../../docs/architecture/domain-service.md)의 Technical Service 예시)은 `account/application/service/notification-service.ts`(인터페이스) + `account/infrastructure/notification/{notification-service-impl.ts,ses-client-provider.ts,sent-email.entity.ts}`(구현체·SES 클라이언트 provider·전용 Entity)로 둔다. 다른 도메인이 공유하지 않는 한 최상위 공유 모듈로 빼지 않는다([shared-modules.md](shared-modules.md) 참고).
