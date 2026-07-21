package com.example.accountservice.account.interfaces.task;

import com.example.accountservice.account.application.command.PayInterestCommand;
import com.example.accountservice.account.application.command.PayInterestService;
import com.example.accountservice.taskqueue.TaskHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Task Queue 입력 어댑터(Interface 레이어) — HTTP Controller가 HTTP 요청을 Application Service에 위임하듯, 이 Task
 * Controller는 Task Queue 메시지를 받아 {@link PayInterestService}를 호출한다 (scheduling.md "Task Controller —
 * Interface 레이어"). 조건 분기나 비즈니스 규칙 없이 위임만 하고, 예외는 그대로 던져 {@code TaskConsumer}가 재시도/DLQ를 판단하게 한다(에러를
 * 삼키지 않는다).
 */
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
    }

    // 이 Task Controller가 소유하는 로컬 payload 뷰 — infrastructure/scheduling/
    // InterestPaymentScheduler가 만드는 JSON과 필드명이 일치해야 한다. 타입을 공유하지 않고 JSON
    // 스키마로만 계약해 레이어 의존 방향(interfaces가 infrastructure를 참조하지 않음)을 지킨다.
    private record Payload(LocalDate date) {}
}
