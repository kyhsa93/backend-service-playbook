# 스케줄링 / 배치 작업 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [scheduling.md](../../../../docs/architecture/scheduling.md) 참고.

## 현재 상태 — 실제 구현됨

`examples/`는 두 개의 실제 주기적 비즈니스 배치를 갖는다 — 둘 다 root의 `Scheduler → task_outbox → Relay → 메시지 큐 → Consumer → TaskController → CommandService` 경로를 문자 그대로 구현한다:

1. **정기 이자 지급**(Feature 1) — 매일 새벽 3시, 모든 ACTIVE 계좌에 잔액의 0.01% 이자를 지급한다. `account/infrastructure/scheduling/InterestPaymentScheduler` → `account/interfaces/task/PayInterestTaskController` → `account/application/command/PayInterestService` → `Account.payInterest()`(Level 1 본질적 멱등, `lastInterestPaidAt`).
2. **월간 카드 사용내역 발송**(Feature 2) → `card/infrastructure/scheduling/CardStatementScheduler` → `card/interfaces/task/SendCardStatementTaskController` → `card/application/command/SendCardStatementService` → Payment BC 사용내역 집계(ACL) + SES 발송, `Card.markStatementSent()`(Level 2 Ledger, `lastStatementSentMonth`).

두 Scheduler 모두 `taskqueue/TaskOutboxWriter`(공용 인프라 패키지, `outbox/`와 형제)를 통해 적재하고, `taskqueue/TaskOutboxPoller`(`@Scheduled(fixedDelay=1000)`)가 드레인해 Task Queue(SQS **FIFO**)로 발행하며, `taskqueue/TaskConsumer`(`SmartLifecycle` 전용 백그라운드 스레드)가 수신해 `taskType`으로 등록된 `TaskHandler`(각 도메인의 Task Controller)를 호출한다. Domain/Integration Event 큐(`outbox/`, 표준 큐)와 완전히 분리된 별도 큐·별도 테이블(`task_outbox`)이다 — Task(명령: "X를 수행하라")와 Domain Event(사실: "X가 일어났다")는 개념적으로 다르기 때문이다([domain-events.md](domain-events.md) 참고).

---

## 최소 요구사항

1. **Scheduler는 Infrastructure 레이어에 배치**한다 — `@Component` + `@Scheduled`, `application/`이 아니라 `<domain>/infrastructure/scheduling/`에 둔다. 실제로 `InterestPaymentScheduler`/`CardStatementScheduler` 모두 이 위치다.
2. **Task 핸들러는 멱등**하다 — `Account.payInterest()`(Level 1)와 `Card.markStatementSent()`(Level 2)가 실제 예시다.
3. **메시지 큐를 쓴다면 DLQ를 기본**으로 한다 — `task-queue-dlq.fifo` + `maxReceiveCount=3`.

---

## Task Outbox 실제 경로

Command 트랜잭션 문맥이 없는 Scheduler(Cron)가 호출 주체이므로, root가 요구하는 "DB 변경과 Task 적재를 같은 트랜잭션에" 원칙은 여기서는 **단일 row insert 자체가 원자적 적재 단위**가 되는 형태로 나타난다(`taskOutboxRepository.save()` 한 줄):

```java
// account/infrastructure/scheduling/InterestPaymentScheduler.java — 실제 코드
@Component
@RequiredArgsConstructor
public class InterestPaymentScheduler {

    private static final String TASK_TYPE = "account.pay-interest";
    private static final String GROUP_ID = "account.interest";

    private final TaskOutboxWriter taskOutboxWriter;

    @Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
    public void enqueueDailyInterestPayment() {
        try {
            LocalDate today = LocalDate.now();
            String dedupId = TASK_TYPE + "-" + today;
            taskOutboxWriter.enqueue(TASK_TYPE, new Payload(today), GROUP_ID, dedupId);
        } catch (Exception e) {
            log.error("이자 지급 Task 적재 실패", e);
            // 예외를 재throw하지 않는다 — 다음 tick(다음날 새벽 3시)에 다시 시도된다.
        }
    }

    private record Payload(LocalDate date) {}
}
```

```java
// taskqueue/TaskOutboxWriter.java — 실제 코드
@Component
@RequiredArgsConstructor
public class TaskOutboxWriter {
    private final TaskOutboxJpaRepository taskOutboxJpaRepository;
    private final ObjectMapper objectMapper;

    public void enqueue(String taskType, Object payload, String groupId, String deduplicationId) {
        try {
            taskOutboxJpaRepository.save(TaskOutboxEntry.create(
                    taskType, objectMapper.writeValueAsString(payload), groupId, deduplicationId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Task 페이로드 직렬화 실패: " + taskType, e);
        }
    }
}
```

일반 Command Service(트랜잭션 문맥이 있는 경우, root 예시의 `CloseAccountService` 같은 케이스)도 같은 `TaskOutboxWriter.enqueue()`를 `@Transactional` 메서드 안에서 호출하면 Aggregate 저장과 Task 적재가 같은 물리 트랜잭션으로 묶인다 — 지금은 두 Feature 모두 Scheduler에서만 호출하므로 이 저장소에 아직 그 조합 예시는 없다.

`taskqueue/TaskOutboxEntry`(`@Entity`, `task_outbox` 테이블)는 `outbox/OutboxEvent`와 동일한 구조지만 `groupId`/`deduplicationId` 컬럼이 추가되어 있다 — FIFO 큐의 `MessageGroupId`/`MessageDeduplicationId`로 그대로 전달되기 때문이다(아래 "Cron 다중 인스턴스 안전성" 참고).

---

## `TaskOutboxPoller`/`TaskConsumer` — `outbox/OutboxPoller`/`OutboxConsumer`와 동일한 구조

```java
// taskqueue/TaskOutboxPoller.java — 실제 코드(발췌)
@Component
@RequiredArgsConstructor
public class TaskOutboxPoller {

    private final TaskOutboxJpaRepository taskOutboxJpaRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;

    @Scheduled(fixedDelay = 1000)
    @Transactional   // payload가 @Lob 컬럼이라 outbox/OutboxPoller와 동일한 이유로 필요
    public void poll() {
        List<TaskOutboxEntry> pending = taskOutboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (TaskOutboxEntry entry : pending) {
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(sqsProperties.taskQueueUrl())
                        .messageBody(entry.getPayload())
                        .messageGroupId(entry.getGroupId())              // FIFO 전용
                        .messageDeduplicationId(entry.getDeduplicationId())  // FIFO 전용
                        .messageAttributes(Map.of("taskType", MessageAttributeValue.builder()
                                .dataType("String").stringValue(entry.getTaskType()).build()))
                        .build());
                entry.markProcessed();
                taskOutboxJpaRepository.save(entry);
            } catch (Exception e) {
                log.error("Task Queue 발행 실패", kv("task_type", entry.getTaskType()), e);
                // processed=false로 남아 다음 tick에서 재시도된다.
            }
        }
    }
}
```

```java
// taskqueue/TaskConsumer.java — 실제 코드(발췌)
@Component
public class TaskConsumer implements SmartLifecycle {

    private final Map<String, TaskHandler> handlers;   // taskType() -> handler, 생성자에서 한 번 구성
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "task-consumer"));
    private volatile boolean running = false;

    public TaskConsumer(SqsClient sqsClient, SqsProperties sqsProperties, List<TaskHandler> taskHandlers) {
        this.handlers = taskHandlers.stream()
                .collect(Collectors.toMap(TaskHandler::taskType, Function.identity()));
    }

    @Override public void start() { running = true; executor.submit(this::pollLoop); }
    @Override public void stop() { /* outbox/OutboxConsumer와 동일한 graceful shutdown */ }

    private void handleMessage(String queueUrl, Message message) {
        String taskType = /* message.messageAttributes().get("taskType").stringValue() */ null;
        try {
            TaskHandler handler = handlers.get(taskType);
            if (handler == null) throw new IllegalStateException("등록된 Task Handler가 없습니다: " + taskType);
            handler.handle(message.body());
            sqsClient.deleteMessage(/* ack */);
        } catch (Exception e) {
            log.error("Task 처리 실패", kv("task_type", taskType), e);
            // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
        }
    }
}
```

`outbox/OutboxConsumer`와 완전히 같은 이유로 `@Scheduled`가 아니라 전용 단일 스레드 `ExecutorService` + `SmartLifecycle`을 쓴다 — `waitTimeSeconds(5)`의 블로킹 long polling을 `@Scheduled` 스레드 풀에 물려두면 `TaskOutboxPoller`/`OutboxPoller` 같은 다른 스케줄 작업의 실행을 지연시킬 위험이 있다(자세한 이유는 [domain-events.md — 백그라운드 루프 설계](domain-events.md#백그라운드-루프-설계--왜-scheduled가-아니라-smartlifecycle--전용-스레드인가) 참고).

**핸들러 라우팅은 `Collectors.toMap`(taskType당 정확히 하나의 핸들러)이다.** `outbox/OutboxConsumer`가 한때 이벤트 타입당 여러 핸들러를 등록해야 하는 요구로 `Collectors.groupingBy`로 바뀐 적이 있는 것과 달리, Task는 "누가 이 명령을 수행하는가"가 항상 정확히 하나의 Task Controller로 결정된다(Task Controller는 HTTP Controller처럼 정확히 하나의 엔드포인트다) — 여러 핸들러가 같은 `taskType`을 두고 경쟁할 개념적 이유가 없으므로 `toMap`이 맞는 설계다.

---

## Task Controller — Interface 레이어, 실제 코드

```java
// account/interfaces/task/PayInterestTaskController.java — 실제 코드
@Component
@RequiredArgsConstructor
public class PayInterestTaskController implements TaskHandler {

    private final PayInterestService payInterestService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "account.pay-interest";
    }

    @Override
    public void handle(String payload) throws Exception {
        Payload parsed = objectMapper.readValue(payload, Payload.class);
        payInterestService.payInterest(new PayInterestCommand(parsed.date()));
        // 예외를 그대로 던진다 — catch/변환하지 않는다. TaskConsumer가 재시도/DLQ를 판단한다.
    }

    private record Payload(LocalDate date) {}
}
```

`HTTP Controller ↔ CommandService` 관계와 완전히 동일하게, Task Controller는 조건 분기나 비즈니스 규칙 없이 위임만 한다. **HTTP Controller의 `@RestControllerAdvice`(`GlobalExceptionHandler`) 같은 catch/에러 응답 변환 계층이 없다** — 그게 root 원칙("에러를 그대로 던진다")의 핵심이다. `PayInterestTaskController`/`SendCardStatementTaskController` 둘 다 `try`/`catch` 없이 CommandService 호출 한 줄이다.

payload는 Scheduler와 Task Controller가 필드명만 맞춘 별도의 로컬 `record`로 각각 정의한다(타입을 공유하지 않는다) — `infrastructure`가 `interfaces`의 타입을 참조하면 레이어 의존 방향(Interface → Application, Infrastructure → Domain/Application이지 Interface 참조 아님)이 깨지기 때문이다.

---

## Cron 다중 인스턴스 안전성 — FIFO 큐 + 날짜/월 기반 dedup

이 저장소는 root가 제시한 두 옵션(FIFO dedup vs ShedLock) 중 **FIFO dedup**을 실제로 채택했다 — 이미 `outbox/` 패턴으로 SQS를 쓰고 있어 인프라 추가 없이 자연스럽다. Task Queue만 FIFO이고 Domain Event 큐(`outbox/`)는 표준 큐로 남아 있다(용도가 다르므로).

| Scheduler | dedupId | groupId |
|---|---|---|
| `InterestPaymentScheduler` | `account.pay-interest-<오늘 날짜>` | `account.interest` |
| `CardStatementScheduler` | `card.send-statement-<이번 달>` | `card.statement` |

같은 날/같은 달 여러 인스턴스가 동시에 tick해도 `task_outbox`에는 여러 행이 쌓일 수 있지만(각 인스턴스가 독립적으로 insert), `TaskOutboxPoller`가 발행할 때 같은 `deduplicationId`로 SQS FIFO의 5분 dedup 윈도우 안에서는 큐에 1건만 들어간다. 큐 레벨에서 걸러지지 못한 극단적인 경우(윈도우를 벗어난 재시도 등)에도 `Account.payInterest()`/`Card.markStatementSent()`의 Level 1/Level 2 멱등성이 최종 방어선이다 — 두 층이 함께 다중 인스턴스 안전성을 보장한다.

큐를 아직 도입하지 않은 단순 배치라면 `ShedLock`(`net.javacrumbs.shedlock`)도 여전히 유효한 대안이다 — 이 저장소는 채택하지 않았을 뿐, 인프라 상황에 따라 선택 가능한 옵션으로 남아 있다.

---

## 멱등성 — 실제 Level 1 / Level 2 예시

```java
// account/domain/Account.java — 실제 코드(발췌), Level 1 — 본질적 멱등
public Optional<Transaction> payInterest(LocalDate today) {
    if (this.status != AccountStatus.ACTIVE) return Optional.empty();
    if (this.lastInterestPaidAt != null && !this.lastInterestPaidAt.isBefore(today)) {
        return Optional.empty();   // 오늘 이미 지급했으면 no-op
    }
    long interestAmount = this.balance.amount() / DAILY_INTEREST_RATE_DENOMINATOR;
    if (interestAmount <= 0) return Optional.empty();   // 이자 0 계산도 no-op — 재실행해도 항상 동일
    // ... 잔액 증가 + lastInterestPaidAt 갱신 + Transaction 생성 ...
}
```

```java
// card/domain/Card.java — 실제 코드(발췌), Level 2 — Ledger
public boolean shouldSendStatement(YearMonth month) {
    return this.status == CardStatus.ACTIVE && !month.equals(this.lastStatementSentMonth);
}

public void markStatementSent(YearMonth month) {
    this.lastStatementSentMonth = month;
}
```

`SendCardStatementService`는 `shouldSendStatement()`가 `false`인 카드는 이메일 발송도, `cardRepository.saveCard()`도 건너뛴다 — at-least-once로 같은 Task가 재실행돼도 재발송하지 않는다. 두 방식 모두 별도의 "처리 기록" 테이블(Level 3 강한 원자성용 ledger)을 두지 않고 Aggregate 필드에 인라인했다 — 판단 기준이 Aggregate 자신의 상태(오늘 날짜, 이번 달)로 충분하기 때문이다. `domain-events.md`의 이벤트 핸들러 멱등성과 동일한 3단계 모델을 그대로 따른다.

---

## DLQ — 실제 SQS 설정

```bash
# localstack/init-sqs.sh — 실제 코드(발췌)
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

`outbox/`의 `domain-events-dlq`와 동일한 구성(`maxReceiveCount=3`)이지만 FIFO 큐 속성(`FifoQueue: true`)이 추가된다. `.env.example`/`docker-compose.yml`의 `SQS_TASK_QUEUE_URL`, `config/SqsProperties.taskQueueUrl()`(`@NotBlank`)이 실제 URL을 주입한다.

---

## 원칙

- **Scheduler는 Infrastructure 레이어**: `<domain>/infrastructure/scheduling/` 패키지, Application/Domain에 `@Scheduled` 사용 금지 — `harness/src/rules/SchedulerInInfrastructureOnly.java`가 검증한다(아래 참고).
- **Scheduler는 적재만**: `@Scheduled` 메서드는 `TaskOutboxWriter.enqueue()` 호출만 한다 — 비즈니스 로직(이자 계산, 통계 집계 등)은 전부 Task Controller 이후(CommandService/Aggregate)에 있다.
- **Task Controller는 Interface 레이어**: `<domain>/interfaces/task/`, HTTP Controller와 동일한 입력 어댑터. 에러를 그대로 던진다(catch/변환 없음).
- **적재는 Task Outbox 경유**: `task_outbox` 테이블 → `TaskOutboxPoller` → SQS FIFO → `TaskConsumer`. Domain Event Outbox(`outbox/`)와 완전히 분리된 테이블·큐다.
- **Task는 멱등하게**: `Account.payInterest()`(Level 1), `Card.markStatementSent()`(Level 2) 둘 다 실제 구현되어 있고 E2E 테스트(`InterestPaymentSchedulingE2ETest`/`CardStatementSchedulingE2ETest`)로 같은 날/같은 달 재적재해도 결과가 바뀌지 않음을 검증한다.
- **DLQ 필수**: `task-queue-dlq.fifo` + `maxReceiveCount=3`, `outbox/`의 DLQ와 동일한 컨벤션.
- **Cron 예외는 명시적 로깅**: 두 Scheduler 모두 `try`/`catch` + `log.error`로 실패를 명시적으로 남기고, 예외를 재throw하지 않는다(다음 tick 재시도에 맡긴다).

---

## harness 검증

`harness/src/rules/SchedulerInInfrastructureOnly.java`(rule: `scheduler-in-infrastructure-only`)가 `domain/`·`application/`에서 `@Scheduled`/`@EnableScheduling` 사용을 찾으면 실패시킨다 — 블록리스트 방식이라, `outbox/OutboxPoller.java`/`taskqueue/TaskOutboxPoller.java`(공용 인프라 패키지, 도메인별 `infrastructure/` 하위가 아님)와 `AccountServiceApplication.java`의 `@EnableScheduling`(부트스트랩 진입점)처럼 이 저장소의 실제 정당한 사용은 통과한다. `InterestPaymentScheduler`/`CardStatementScheduler`는 각각 `account/infrastructure/scheduling/`·`card/infrastructure/scheduling/`에 있어 화이트리스트 방식이었어도 통과했겠지만, 블록리스트로도 동일하게 통과를 정확히 판정한다.

`harness/src/rules/NoSilentCatch.java`(rule: `no-silent-catch`)는 `application/`·`infrastructure/`의 빈 catch 블록만 잡는다 — Task Controller(`interfaces/task/`)는 애초에 catch 자체가 없으므로(예외를 그대로 던짐) 이 규칙의 스코프 밖이지만 원칙적으로 위반할 수 없는 설계다.

---

### 관련 문서

- [domain-events.md](domain-events.md) — `OutboxWriter`/`OutboxPoller`/`OutboxConsumer`의 실제 구현(`taskqueue/`의 대응 구조), Domain Event vs Task Queue 구분, 멱등성 3단계
- [layer-architecture.md](layer-architecture.md) — 레이어 배치 원칙
- [graceful-shutdown.md](graceful-shutdown.md) — `TaskConsumer`의 `SmartLifecycle.stop()` graceful 종료
- [shared-modules.md](shared-modules.md) — `taskqueue/` 패키지 배치 컨벤션
