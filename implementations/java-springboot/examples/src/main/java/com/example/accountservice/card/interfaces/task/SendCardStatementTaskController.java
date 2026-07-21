package com.example.accountservice.card.interfaces.task;

import com.example.accountservice.card.application.command.SendCardStatementCommand;
import com.example.accountservice.card.application.command.SendCardStatementService;
import com.example.accountservice.taskqueue.TaskHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Task Queue 입력 어댑터(Interface 레이어) — HTTP Controller가 HTTP 요청을 Application Service에 위임하듯, 이 Task
 * Controller는 Task Queue 메시지를 받아 {@link SendCardStatementService}를 호출한다 (scheduling.md "Task
 * Controller — Interface 레이어"). 조건 분기나 비즈니스 규칙 없이 위임만 하고, 예외는 그대로 던져 {@code TaskConsumer}가 재시도/DLQ를
 * 판단하게 한다.
 */
@Component
@RequiredArgsConstructor
public class SendCardStatementTaskController implements TaskHandler {

    private final SendCardStatementService sendCardStatementService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "card.send-statement";
    }

    @Override
    public void handle(String payload) throws Exception {
        Payload parsed = objectMapper.readValue(payload, Payload.class);
        sendCardStatementService.sendStatements(new SendCardStatementCommand(parsed.month()));
    }

    // 이 Task Controller가 소유하는 로컬 payload 뷰 — infrastructure/scheduling/CardStatementScheduler가
    // 만드는 JSON과 필드명이 일치해야 한다.
    private record Payload(YearMonth month) {}
}
