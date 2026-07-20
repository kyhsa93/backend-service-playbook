package com.example.accountservice.outbox

import com.example.accountservice.config.SqsProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

/**
 * SQS를 long polling(`ReceiveMessageRequest.waitTimeSeconds`)으로 수신 대기하다가 메시지를 받으면
 * eventType(`MessageAttributes`)으로 [EventHandlerRegistry]에서 핸들러를 찾아 호출한다.
 *
 * 이 저장소는 코루틴을 쓰지 않는다(scheduling.md — Spring MVC + JPA 블로킹 스택과 자연스럽게
 * 맞물리는 전통적인 스레드 기반 실행을 쓴다). `@Scheduled`로 표현하지 않은 이유는 `@Scheduled`가
 * "일정 주기로 짧게 실행하고 반환"하는 작업(예: [OutboxPoller])에 맞는 추상화인 반면, 이 Consumer는
 * `waitTimeSeconds(5)` 동안 블로킹하며 무한히 반복하는 하나의 긴 루프이기 때문이다 — 전용 스레드가
 * 이 루프의 의도(앱 부트스트랩 시 단 한 번 시작되는 백그라운드 워커)를 더 직접적으로 표현한다.
 *
 * 핸들러 성공 → 메시지 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) → 삭제하지 않는다 —
 * SQS의 visibility timeout이 지나면 자동 재전달된다(at-least-once). 이 저장소가 요구하는
 * EventHandler 멱등성(domain-events.md)이 바로 이 재전달을 전제한다.
 *
 * 시작/종료는 Spring 프레임워크 리스너 애노테이션 + `DisposableBean` 조합이 아니라
 * [SmartLifecycle] 하나로 표현한다 — 그 리스너 애노테이션은 이 저장소에서 Domain Event Handler를
 * 뜻하는 표식(harness의 event-placement 규칙이 `application/event/` 배치를 강제)이므로, 프레임워크
 * 생명주기 콜백에 같은 애노테이션을 쓰면 의미가 겹친다. `SmartLifecycle.start()`는 컨텍스트가 뜬
 * 직후, `stop()`은 Graceful Shutdown 시 호출된다.
 */
@Component
class OutboxConsumer(
    private val sqsClient: SqsClient,
    private val registry: EventHandlerRegistry,
    private val sqsProperties: SqsProperties,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(OutboxConsumer::class.java)

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    // 앱 부트스트랩이 끝난 뒤 단 한 번 시작되는 싱글턴 백그라운드 루프다 — 요청마다 새로 만들어지지
    // 않는다.
    override fun start() {
        running = true
        workerThread =
            Thread(::pollLoop, "outbox-consumer").apply {
                isDaemon = false
                start()
            }
    }

    // Graceful Shutdown 시(Spring이 컨텍스트를 닫으며 SmartLifecycle.stop()을 호출하는 시점) 루프를
    // 멈춘다. 진행 중인 ReceiveMessageRequest(최대 waitTimeSeconds)는 끝까지 기다린 뒤 종료한다 —
    // graceful-shutdown.md의 "Scheduler는 종료 대기를 명시적으로 설정한다" 원칙을 전용 스레드에
    // 적용한 것이다.
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
                            .queueUrl(sqsProperties.domainEventQueueUrl)
                            .maxNumberOfMessages(10)
                            .messageAttributeNames("eventType", "eventId")
                            .waitTimeSeconds(5)
                            .build(),
                    )
                result.messages().forEach { handle(it) }
            } catch (e: Exception) {
                if (running) {
                    logger.error("SQS 수신 실패", e)
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun handle(message: Message) {
        val eventType = message.messageAttributes()["eventType"]?.stringValue()
        val eventId = message.messageAttributes()["eventId"]?.stringValue() ?: ""
        try {
            if (eventType == null) throw IllegalStateException("eventType 메시지 속성이 없습니다.")
            registry.dispatch(eventType, eventId, message.body())
            sqsClient.deleteMessage(
                DeleteMessageRequest
                    .builder()
                    .queueUrl(sqsProperties.domainEventQueueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build(),
            )
        } catch (e: Exception) {
            // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
            logger
                .atError()
                .addKeyValue("event_type", eventType)
                .setCause(e)
                .log("이벤트 처리 실패")
        }
    }

    companion object {
        private const val SHUTDOWN_JOIN_TIMEOUT_MS = 10_000L
    }
}
