package com.example.accountservice.taskqueue

import com.example.accountservice.config.SqsProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

/**
 * Task Queue(SQS FIFO) 전용 Consumer — [com.example.accountservice.outbox.OutboxConsumer](Domain/
 * Integration Event Queue 전용)와 의도적으로 별개의 컴포넌트다. 두 Consumer는 서로 다른 큐를
 * 구독하고 서로 다른 레지스트리([TaskHandlerRegistry] vs
 * [com.example.accountservice.outbox.EventHandlerRegistry])로 라우팅한다 — root
 * scheduling.md/domain-events.md가 규정하는 "Task Queue(명령)와 Domain Event(사실)는 의미 단위가
 * 다르다"는 구분을 컴포넌트 단위로도 유지한다(하나의 Consumer가 두 큐를 다 처리하도록 합치지
 * 않는다).
 *
 * 나머지 구조(전용 스레드로 표현하는 이유, 핸들러 성공=ack/실패=재전달)는 OutboxConsumer와 동일하다
 * — 그 클래스의 KDoc을 참고한다.
 */
@Component
class TaskQueueConsumer(
    private val sqsClient: SqsClient,
    private val registry: TaskHandlerRegistry,
    private val sqsProperties: SqsProperties,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(TaskQueueConsumer::class.java)

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    override fun start() {
        running = true
        workerThread =
            Thread(::pollLoop, "task-queue-consumer").apply {
                isDaemon = false
                start()
            }
    }

    override fun stop() {
        running = false
        workerThread?.join(SHUTDOWN_JOIN_TIMEOUT_MS)
    }

    override fun isRunning(): Boolean = running

    private fun pollLoop() {
        while (running) {
            try {
                val result =
                    sqsClient.receiveMessage(
                        ReceiveMessageRequest
                            .builder()
                            .queueUrl(sqsProperties.taskQueueUrl)
                            .maxNumberOfMessages(10)
                            .messageAttributeNames("taskType", "taskId")
                            .waitTimeSeconds(5)
                            .build(),
                    )
                result.messages().forEach { handle(it) }
            } catch (e: Exception) {
                if (running) {
                    logger.error("Task Queue(SQS) 수신 실패", e)
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun handle(message: Message) {
        val taskType = message.messageAttributes()["taskType"]?.stringValue()
        val taskId = message.messageAttributes()["taskId"]?.stringValue() ?: ""
        try {
            if (taskType == null) throw IllegalStateException("taskType 메시지 속성이 없습니다.")
            registry.dispatch(taskType, message.body())
            sqsClient.deleteMessage(
                DeleteMessageRequest
                    .builder()
                    .queueUrl(sqsProperties.taskQueueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build(),
            )
        } catch (e: Exception) {
            // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다(at-least-once).
            // maxReceiveCount(3)를 넘기면 SQS가 자동으로 DLQ로 이동시킨다(scheduling.md DLQ 절).
            logger
                .atError()
                .addKeyValue("task_type", taskType)
                .addKeyValue("task_id", taskId)
                .setCause(e)
                .log("Task 처리 실패")
        }
    }

    companion object {
        private const val SHUTDOWN_JOIN_TIMEOUT_MS = 10_000L
    }
}
