# 스케줄링 / 배치 작업 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [scheduling.md](../../../../docs/architecture/scheduling.md) 참고.

## 현재 상태

`examples/`는 `@Scheduled`/`@EnableScheduling`을 실제로 쓴다 — `outbox/OutboxPoller.poll()`이 `@Scheduled(fixedDelay = 1000)`으로 Outbox 테이블을 폴링해 SQS로 발행하고, `AccountServiceApplication`에 `@EnableScheduling`이 선언되어 있다([domain-events.md](domain-events.md) 참고). 다만 이 저장소는 그 이상의 "일반적인 배치 작업" 유스케이스(예: 만료 계좌 정리처럼 비즈니스 로직 자체를 주기 실행하는 것)는 아직 갖고 있지 않다 — `OutboxPoller`는 Outbox 드레인 전용이고, SQS 수신은 블로킹 long-poll 성격이라 `@Scheduled`가 아니라 `OutboxConsumer`(`SmartLifecycle` 전용 백그라운드 스레드)가 담당한다([domain-events.md](domain-events.md) 참고). 아래는 여전히 미구현인 "만료 계좌 정리 배치" 같은 진짜 주기적 비즈니스 작업이 생길 때 참고할 목표 형태다.

---

## 최소 요구사항

1. **Scheduler는 Infrastructure 레이어에 배치**한다 — `@Component` + `@Scheduled`, `application/`이 아니라 `infrastructure/`에 둔다.
2. **Task 핸들러는 멱등**하다.
3. **메시지 큐를 쓴다면 DLQ를 기본**으로 한다.

---

## `@EnableScheduling` — 애플리케이션 진입점에 활성화

```java
// AccountServiceApplication.java — 실제 코드
@SpringBootApplication
@EnableScheduling   // outbox/OutboxPoller의 @Scheduled(fixedDelay = 1000)을 활성화한다
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

`@EnableScheduling`이 없으면 `@Scheduled` 애노테이션은 아무 효과가 없다 — 조용히 무시되므로 누락하기 쉽다. 이 저장소는 실제로 `OutboxPoller.poll()` 하나가 이 애노테이션에 의존한다 — 앞으로 배치 작업이 추가되면 같은 `@EnableScheduling` 하나로 함께 활성화된다.

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
| `fixedDelay = 1000` | 이전 실행 **종료** 후 1초 뒤 재실행 | 별도 프로세스로 Outbox를 폴링하는 경우 — 이 저장소의 실제 `OutboxPoller.poll()`이 정확히 이 속성을 쓴다([domain-events.md](domain-events.md) 참고) |
| `fixedRate = 1000` | 이전 실행 **시작** 후 1초 뒤 재실행 (겹칠 수 있음) | 드물게 사용 — 겹침 위험 인지 필요 |

폴링 성격의 Poller는 `fixedDelay`가 안전하다 — 이전 폴링이 끝나기 전에 다음 폴링이 겹쳐 시작되는 것을 방지한다. Spring의 기본 스케줄러는 단일 스레드이므로 이 보장은 프레임워크가 대신 해준다(nestjs의 `isPolling` 플래그와 동일한 효과). 반면 SQS 수신처럼 `waitTimeSeconds`로 초 단위 블로킹이 반복되는 긴 루프는 `@Scheduled` 스레드 풀에 계속 물려두면 다른 스케줄 작업의 실행을 지연시킬 위험이 있다 — 이 저장소의 `OutboxConsumer`는 그래서 `@Scheduled`가 아니라 전용 단일 스레드 `ExecutorService` + `SmartLifecycle`을 쓴다([domain-events.md](domain-events.md) 참고).

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
    Account account = accountRepository
            .findAccounts(new AccountFindQuery(0, 1, command.accountId(), command.requesterId(), null))
            .accounts().stream().findFirst().orElseThrow(/* ... */);
    account.close();
    accountRepository.saveAccount(account);   // Account 저장 + (Outbox 저장, domain-events.md 참고)
    taskOutboxRepository.save(TaskOutboxEntry.of("account.archive", account.getAccountId()));  // 같은 트랜잭션
}
```

`accountRepository.saveAccount()`와 `taskOutboxRepository.save()`가 같은 `@Transactional` 메서드 안에 있으므로 Spring이 하나의 물리 트랜잭션으로 커밋/롤백한다 — 별도의 분산 트랜잭션 처리가 필요 없다. "DB 변경과 적재를 같은 트랜잭션에 묶는다"는 원리는 [domain-events.md](domain-events.md)의 `OutboxWriter`/`OutboxPoller`와 동일하다 — 실제로 지금은 둘 다 저장은 같은 트랜잭션에서 하고, 드레인은 `@Scheduled` 폴링(`OutboxPoller.poll()`, `fixedDelay=1000`)으로 비동기 처리한다는 점까지 같다. 제안된 `TaskOutboxRelay`도 같은 패턴을 그대로 따르면 된다.

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

- [domain-events.md](domain-events.md) — `OutboxWriter`/`OutboxPoller`/`OutboxConsumer`의 실제 구현(`@Scheduled` 폴링 + SQS를 통한 비동기 드레인), 멱등성 3단계
- [layer-architecture.md](layer-architecture.md) — 레이어 배치 원칙
- [graceful-shutdown.md](graceful-shutdown.md) — Scheduler의 종료 시 처리
