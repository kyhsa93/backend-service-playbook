package com.example.accountservice.account.infrastructure.notification

import com.example.accountservice.account.application.service.NotificationService
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmail
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmailJpaRepository
import com.example.accountservice.config.SesProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.Body
import software.amazon.awssdk.services.ses.model.Content
import software.amazon.awssdk.services.ses.model.Destination
import software.amazon.awssdk.services.ses.model.Message
import software.amazon.awssdk.services.ses.model.SendEmailRequest

@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    private val sesProperties: SesProperties,
) : NotificationService {
    private val logger = LoggerFactory.getLogger(NotificationServiceImpl::class.java)

    override fun sendEmail(
        accountId: String,
        eventType: String,
        sourceEventId: String,
        recipient: String,
        subject: String,
        body: String,
    ) {
        // Level 2(Ledger) 멱등성 — 이 Outbox 이벤트가 이미 이메일 발송으로 이어졌다면 재발송하지
        // 않는다. Relay가 at-least-once로 재시도하는 동안 SES 호출은 성공했지만 processed=true
        // 커밋 전에 프로세스가 죽는 경우, 다음 재시도에서 이 체크가 중복 발송을 막는다.
        if (sentEmailJpaRepository.existsBySourceEventId(sourceEventId)) {
            logger
                .atInfo()
                .addKeyValue("account_id", accountId)
                .addKeyValue("event_type", eventType)
                .addKeyValue("source_event_id", sourceEventId)
                .log("이미 발송된 이벤트 — 중복 발송 스킵")
            return
        }

        val request =
            SendEmailRequest
                .builder()
                .source(sesProperties.senderEmail)
                .destination(Destination.builder().toAddresses(recipient).build())
                .message(
                    Message
                        .builder()
                        .subject(
                            Content
                                .builder()
                                .data(subject)
                                .charset("UTF-8")
                                .build(),
                        ).body(
                            Body
                                .builder()
                                .text(
                                    Content
                                        .builder()
                                        .data(body)
                                        .charset("UTF-8")
                                        .build(),
                                ).build(),
                        ).build(),
                ).build()

        val result = sesClient.sendEmail(request)

        sentEmailJpaRepository.save(
            SentEmail.create(
                accountId = accountId,
                eventType = eventType,
                sourceEventId = sourceEventId,
                recipient = recipient,
                subject = subject,
                sesMessageId = result.messageId(),
            ),
        )

        logger
            .atInfo()
            .addKeyValue("account_id", accountId)
            .addKeyValue("event_type", eventType)
            .addKeyValue("recipient", recipient)
            .addKeyValue("ses_message_id", result.messageId())
            .log("이메일 발송됨")
    }
}
