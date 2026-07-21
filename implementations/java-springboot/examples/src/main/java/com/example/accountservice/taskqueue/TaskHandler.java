package com.example.accountservice.taskqueue;

/**
 * Task Queue 메시지 한 건을 처리하는 계약. {@code taskType()}은 Scheduler가 {@link TaskOutboxWriter#enqueue}에 넘긴
 * taskType과 일치해야 {@link TaskConsumer}가 올바른 핸들러로 라우팅한다. 구현체는 각 도메인의 {@code interfaces/task/}에 위치하는
 * Task Controller다 — HTTP Controller가 Application Service에 위임하듯 이 핸들러도 Command Service를 호출할 뿐 비즈니스
 * 로직을 갖지 않는다 (scheduling.md "Task Controller — Interface 레이어"). 예외를 삼키지 않고 그대로 던져야 {@link
 * TaskConsumer}가 메시지를 삭제하지 않고 재시도(at-least-once)/DLQ로 넘길 수 있다.
 */
public interface TaskHandler {

    String taskType();

    void handle(String payload) throws Exception;
}
