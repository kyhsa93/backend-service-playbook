package com.example.accountservice.taskqueue

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * `@Scheduled`'s default thread pool size is 1 — this repository has 6 `@Scheduled` beans
 * (`OutboxPoller` + `TaskOutboxPoller` + `InterestPaymentScheduler` + `CardStatementScheduler` +
 * `SpendingAnalysisScheduler` + `SpendingForecastScheduler`), so at the default pool size they can
 * block each other (docs/architecture/scheduling.md "Registering a Scheduler — a dedicated thread
 * pool"). A dedicated [TaskScheduler] bean increases the pool size to prevent this.
 */
@Configuration
class SchedulingConfig {
    @Bean
    fun taskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 6
            setThreadNamePrefix("scheduled-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(20)
        }
}
