package com.example.accountservice.card.infrastructure.scheduling;

import com.example.accountservice.taskqueue.TaskOutboxWriter;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매월 1회 모든 활성 카드에 대해 지난 한 달 사용내역 안내를 적재한다(월간 카드 사용내역 발송, scheduling.md Feature 2). Scheduler는 비즈니스
 * 로직을 직접 실행하지 않고 Task Queue에 적재(enqueue)만 한다 — 실제 통계 계산·발송은 {@code
 * card/interfaces/task/SendCardStatementTaskController} → {@code SendCardStatementService}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class CardStatementScheduler {

    private static final Logger log = LoggerFactory.getLogger(CardStatementScheduler.class);
    private static final String TASK_TYPE = "card.send-statement";
    private static final String GROUP_ID = "card.statement";

    private final TaskOutboxWriter taskOutboxWriter;

    // 월 기반 deduplicationId로 여러 인스턴스가 같은 달 1일 동시에 tick해도 Task Queue에는 1건만
    // 들어간다(scheduling.md "Cron 다중 인스턴스 안전성"). 예외는 명시적으로 로깅만 하고 재throw하지
    // 않는다 — 다음 tick(다음 달 1일 새벽 4시)에 다시 시도된다.
    @Scheduled(cron = "0 0 4 1 * *") // 매월 1일 새벽 4시
    public void enqueueMonthlyStatement() {
        try {
            YearMonth month = YearMonth.now();
            String dedupId = TASK_TYPE + "-" + month;
            taskOutboxWriter.enqueue(TASK_TYPE, new Payload(month), GROUP_ID, dedupId);
        } catch (Exception e) {
            log.error("카드 사용내역 안내 Task 적재 실패", e);
        }
    }

    // 이 Scheduler가 소유하는 로컬 payload 뷰 — card/interfaces/task/SendCardStatementTaskController가
    // 소유한 별도의 payload 레코드와 JSON 스키마(필드명)만으로 계약한다(account/infrastructure/scheduling/
    // InterestPaymentScheduler와 동일한 이유).
    private record Payload(YearMonth month) {}
}
