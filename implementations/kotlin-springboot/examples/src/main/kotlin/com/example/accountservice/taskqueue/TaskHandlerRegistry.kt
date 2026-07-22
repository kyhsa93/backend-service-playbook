package com.example.accountservice.taskqueue

import com.example.accountservice.account.interfaces.task.PayInterestTaskController
import com.example.accountservice.card.interfaces.task.SendCardStatementTaskController
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Maps taskType (`task_outbox.task_type` = SQS `MessageAttributes.taskType`) to a handler function.
 * [TaskQueueConsumer] routes to this registry whenever it receives a message from SQS — the same shape
 * as [com.example.accountservice.outbox.EventHandlerRegistry] (building a `Map<taskType, handler>` at
 * constructor-injection time), except that unlike a Domain Event (1:N, subscribed to by multiple
 * handlers), a Task has exactly one handler per taskType (root domain-events.md "Task Queue vs Domain
 * Event" — a 1:1 handler count).
 *
 * The payload (JSON) is parsed here and passed to the Task Controller as typed arguments — the same
 * division of responsibility as EventHandlerRegistry, which deserializes the payload into a Domain Event
 * type before passing it to the handler.
 */
@Component
class TaskHandlerRegistry(
    private val objectMapper: ObjectMapper,
    private val payInterestTaskController: PayInterestTaskController,
    private val sendCardStatementTaskController: SendCardStatementTaskController,
) {
    private val logger = LoggerFactory.getLogger(TaskHandlerRegistry::class.java)

    private val handlers: Map<String, (payload: String) -> Unit> =
        mapOf(
            "account.pay-interest" to { payload ->
                val date = LocalDate.parse(objectMapper.readTree(payload).get("date").asText())
                payInterestTaskController.payInterest(date)
            },
            "card.send-statement" to { payload ->
                val yearMonth = objectMapper.readTree(payload).get("yearMonth").asText()
                sendCardStatementTaskController.sendStatements(yearMonth)
            },
        )

    /** The set of registered taskTypes — for diagnostics/testing. */
    fun registeredTaskTypes(): Set<String> = handlers.keys

    /**
     * Called every time [TaskQueueConsumer] receives one SQS message. If no handler is registered, it
     * just logs a warning and returns quietly (there's no reason to retry an unknown taskType forever).
     * If the handler throws, the exception propagates as-is so [TaskQueueConsumer] doesn't delete the
     * message and instead leaves it to SQS redelivery (at-least-once).
     */
    fun dispatch(
        taskType: String,
        payload: String,
    ) {
        val handler = handlers[taskType]
        if (handler == null) {
            logger.warn("Unknown taskType: {}", taskType)
            return
        }
        handler(payload)
    }
}
