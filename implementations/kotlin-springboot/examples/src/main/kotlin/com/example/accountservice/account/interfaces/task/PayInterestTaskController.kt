package com.example.accountservice.account.interfaces.task

import com.example.accountservice.account.application.command.PayInterestService
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The Interface input adapter for the Task Queue (`account.pay-interest`) (scheduling.md, "Task
 * Controller — the Interface layer"). Just as an HTTP Controller receives an HTTP request and delegates
 * to an Application Service, this component is the entry point that
 * [com.example.accountservice.taskqueue.TaskHandlerRegistry] calls after deserializing an SQS message.
 *
 * It only delegates to the Command, with no logic of its own — no conditional branching or business
 * rules are added here. Exceptions are thrown as-is.
 * [com.example.accountservice.taskqueue.TaskQueueConsumer] catches them and, instead of deleting the
 * message, leaves it to SQS redelivery (at-least-once) — this differs from the HTTP Controller's
 * `@ExceptionHandler` conversion pattern.
 */
@Component
class PayInterestTaskController(
    private val payInterestService: PayInterestService,
) {
    fun payInterest(payDate: LocalDate) {
        payInterestService.payInterest(payDate)
    }
}
