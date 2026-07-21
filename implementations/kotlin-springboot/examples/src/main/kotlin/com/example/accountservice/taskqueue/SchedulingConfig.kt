package com.example.accountservice.taskqueue

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * `@Scheduled`의 기본 스레드 풀은 크기 1이다 — Task Queue 도입 이전에는 `OutboxPoller` 하나뿐이라
 * 문제가 없었지만, 이제 `OutboxPoller` + `TaskOutboxPoller` + `InterestPaymentScheduler` +
 * `CardStatementScheduler`까지 `@Scheduled` 빈이 4개로 늘어 서로를 블로킹할 수 있다
 * (docs/architecture/scheduling.md "Scheduler 등록 — 전용 스레드 풀"). 전용 [TaskScheduler] 빈으로
 * 풀 크기를 늘려 이를 방지한다.
 */
@Configuration
class SchedulingConfig {
    @Bean
    fun taskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 4
            setThreadNamePrefix("scheduled-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(20)
        }
}
