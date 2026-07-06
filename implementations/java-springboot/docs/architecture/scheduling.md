# 스케줄링 / 배치 작업 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [scheduling.md](../../../../docs/architecture/scheduling.md) 참고.

## 현재 상태

`examples/`에 `@Scheduled`/`@EnableScheduling` 사용처가 전혀 없다 — 이 저장소는 주기적 배치 작업이 필요한 유스케이스(예: 만료 계좌 정리)를 아직 갖고 있지 않다. [domain-events.md](domain-events.md)에서 제안한 `OutboxRelay`/`EventConsumer`가 이 저장소에 `@Scheduled`를 도입하는 첫 번째 실질적 계기가 될 것이다.

---

## 최소 요구사항

1. **Scheduler는 Infrastructure 레이어에 배치**한다 — `@Component` + `@Scheduled`, `application/`이 아니라 `infrastructure/`에 둔다.
2. **Task 핸들러는 멱등**하다.
3. **메시지 큐를 쓴다면 DLQ를 기본**으로 한다.

---

## `@EnableScheduling` — 애플리케이션 진입점에 활성화

```java
// AccountServiceApplication.java — 추가 필요
@SpringBootApplication
@EnableScheduling
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

`@EnableScheduling`이 없으면 `@Scheduled` 애노테이션은 아무 효과가 없다 — 조용히 무시되므로 누락하기 쉽다.

---

## Scheduler는 적재만 — `@Scheduled`가 직접 비즈니스 로직을 실행하지 않는다

root 원칙: Scheduler는 Task를 큐에 적재(enqueue)하는 것만 하고, 실제 실행은 Consumer가 담당한다.

```java
// infrastructure/scheduling/AccountCleanupScheduler.java — 제안
@Component
@RequiredArgsConstructor
public class AccountCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountCleanupScheduler.class);

    private final SqsClient sqsClient;

    @Value("${app.task-queue.url}")
    private String queueUrl;

    @Scheduled(cron = "0 0 3 * * *")   // 매일 새벽 3시
    public void enqueueDailyCleanup() {
        try {
            String dedupId = "account.cleanup-expired-" + LocalDate.now();
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody("{}")
                    .messageGroupId("account.cleanup")
                    .messageDeduplicationId(dedupId)     // 날짜 기반 — 다중 인스턴스가 동시에 적재해도 1건만 처리
                    .build());
        } catch (Exception e) {
            log.error("Cron enqueue 실패", e);
            // 예외를 재throw하지 않는다 — 다음 tick에서 재시도
        }
    }
}
```

**`@Scheduled` 예외 처리 — 명시적 try-catch 필수**: Spring의 `@Scheduled` 메서드가 던진 예외는 기본적으로 로그에 스택트레이스만 남기고 **다음 스케줄 실행을 막지 않지만, 예외 발생 자체가 조용히 지나가기 쉽다** — `log.error`로 명시적으로 기록하지 않으면 실패가 관찰 불가능해진다.

**`cron` vs `fixedDelay`/`fixedRate`**:

| 속성 | 의미 | 사용 예 |
|---|---|---|
| `cron = "0 0 3 * * *"` | 특정 시각에 실행 (cron 표현식) | 일 1회 배치 |
| `fixedDelay = 1000` | 이전 실행 **종료** 후 1초 뒤 재실행 | Outbox 폴링([domain-events.md](domain-events.md)의 `OutboxRelay`) |
| `fixedRate = 1000` | 이전 실행 **시작** 후 1초 뒤 재실행 (겹칠 수 있음) | 드물게 사용 — 겹침 위험 인지 필요 |

폴링 성격의 Relay/Consumer는 `fixedDelay`가 안전하다 — 이전 폴링이 끝나기 전에 다음 폴링이 겹쳐 시작되는 것을 방지한다.

---

## 다중 인스턴스 안전성 — `@Scheduled`의 근본적 한계

**Spring의 `@Scheduled`는 기본적으로 인스턴스마다 독립 실행된다** — 이 애플리케이션이 여러 인스턴스(Pod, ECS Task)로 스케일 아웃되면, 같은 cron 표현식이 모든 인스턴스에서 동시에 실행된다. 위 예시의 `messageDeduplicationId`(날짜 기반)가 이 문제를 FIFO 큐의 중복 제거 기능으로 해결하는 이유다 — 여러 인스턴스가 같은 날 enqueue해도 큐에는 1건만 들어간다.

큐를 아직 도입하지 않은 단순한 배치라면 `ShedLock`(`net.javacrumbs.shedlock`) 같은 분산 락 라이브러리로 "이 tick은 하나의 인스턴스만 실행"을 보장할 수 있다:

```groovy
// build.gradle — ShedLock 도입 시 대안
implementation 'net.javacrumbs.shedlock:shedlock-spring:5.16.0'
implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0'
```

```java
@Scheduled(cron = "0 0 3 * * *")
@SchedulerLock(name = "accountCleanup", lockAtMostFor = "10m")
public void enqueueDailyCleanup() { /* ... */ }
```

**두 방식의 선택 기준**: 이미 메시지 큐(SQS 등)를 쓰고 있다면 FIFO dedup이 자연스럽다(root의 Task Outbox 경로와 일치). 큐가 없는 단순 배치라면 ShedLock이 인프라 추가 없이(DB 테이블 하나로) 같은 효과를 낸다.

---

## Task Outbox 패턴 — DB 변경과 Task 적재의 원자성

Command Service에서 DB 변경과 Task 적재가 원자적으로 묶여야 한다면, [domain-events.md](domain-events.md)와 동일한 Outbox 경로를 Task에도 적용한다:

```java
// application/command/CloseAccountService.java 내부에서 (제안)
@Transactional
public void close(CloseAccountCommand command) {
    Account account = accountRepository.findByAccountIdAndOwnerId(/* ... */).orElseThrow(/* ... */);
    account.close();
    accountRepository.save(account);          // Account 저장 + (Outbox 저장, domain-events.md 참고)
    taskOutboxRepository.save(TaskOutboxEntry.of("account.archive", account.getAccountId()));  // 같은 트랜잭션
}
```

`accountRepository.save()`와 `taskOutboxRepository.save()`가 같은 `@Transactional` 메서드 안에 있으므로 Spring이 하나의 물리 트랜잭션으로 커밋/롤백한다 — 별도의 분산 트랜잭션 처리가 필요 없다. 이후 `TaskOutboxRelay`(`@Scheduled`)가 폴링하여 메시지 큐로 전송하는 흐름은 [domain-events.md](domain-events.md)의 `OutboxRelay`와 동일한 구조다.

---

## DLQ — SQS 설정

```java
// SQS 큐 생성 시 (Terraform/CDK 등 인프라 코드에서, 애플리케이션 코드 범위 밖)
// RedrivePolicy: { maxReceiveCount: 3, deadLetterTargetArn: "<dlq-arn>" }
```

`maxReceiveCount` 초과 시 DLQ로 자동 이동한다 — 애플리케이션 코드는 이 설정에 관여하지 않지만, DLQ 메시지 수에 대한 CloudWatch 알람은 반드시 구성해야 한다(root 원칙).

---

## 원칙

- **Scheduler는 Infrastructure 레이어**: `infrastructure/scheduling/` 패키지, Application/Domain에 `@Scheduled` 사용 금지.
- **Scheduler는 적재만**: `@Scheduled` 메서드는 큐 발행 또는 Outbox insert만 한다.
- **`@EnableScheduling` 누락 확인**: 없으면 `@Scheduled`가 조용히 무효화된다.
- **다중 인스턴스 안전성**: FIFO dedup 또는 ShedLock 중 인프라 상황에 맞는 것을 선택한다.
- **`@Scheduled` 예외는 명시적 로깅**: 실패가 조용히 묻히지 않도록 한다.
- **Task Outbox로 원자성 보장**: DB 변경과 Task 적재를 같은 `@Transactional` 안에 둔다.

---

### 관련 문서

- [domain-events.md](domain-events.md) — `OutboxRelay`/`EventConsumer`의 `@Scheduled` 활용, 멱등성 3단계
- [layer-architecture.md](layer-architecture.md) — 레이어 배치 원칙
- [graceful-shutdown.md](graceful-shutdown.md) — Scheduler의 종료 시 처리
