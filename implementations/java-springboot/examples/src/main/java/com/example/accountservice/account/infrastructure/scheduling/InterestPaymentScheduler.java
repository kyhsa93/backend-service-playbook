package com.example.accountservice.account.infrastructure.scheduling;

import com.example.accountservice.taskqueue.TaskOutboxWriter;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 1회 모든 활성 계좌에 이자를 지급하는 배치를 적재한다(정기 이자 지급, scheduling.md Feature 1). Scheduler는 비즈니스 로직을 직접 실행하지
 * 않고 Task Queue에 적재(enqueue)만 한다 — 실제 이자 계산·지급은 {@code
 * account/interfaces/task/PayInterestTaskController} → {@code PayInterestService}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class InterestPaymentScheduler {

    private static final Logger log = LoggerFactory.getLogger(InterestPaymentScheduler.class);
    private static final String TASK_TYPE = "account.pay-interest";
    private static final String GROUP_ID = "account.interest";

    private final TaskOutboxWriter taskOutboxWriter;

    // 날짜 기반 deduplicationId로 여러 인스턴스가 같은 날 동시에 tick해도 Task Queue에는 1건만
    // 들어간다(scheduling.md "Cron 다중 인스턴스 안전성"). 예외는 명시적으로 로깅만 하고 재throw하지
    // 않는다 — 다음 tick(다음날 새벽 3시)에 다시 시도된다.
    @Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
    public void enqueueDailyInterestPayment() {
        try {
            LocalDate today = LocalDate.now();
            String dedupId = TASK_TYPE + "-" + today;
            taskOutboxWriter.enqueue(TASK_TYPE, new Payload(today), GROUP_ID, dedupId);
        } catch (Exception e) {
            log.error("이자 지급 Task 적재 실패", e);
        }
    }

    // 이 Scheduler가 소유하는 로컬 payload 뷰 — account/interfaces/task/PayInterestTaskController가
    // 소유한 별도의 payload 레코드와 JSON 스키마(필드명)만으로 계약한다. Infrastructure가 Interfaces
    // 레이어의 타입을 직접 참조하지 않기 위함이다(레이어 의존 방향 유지).
    private record Payload(LocalDate date) {}
}
