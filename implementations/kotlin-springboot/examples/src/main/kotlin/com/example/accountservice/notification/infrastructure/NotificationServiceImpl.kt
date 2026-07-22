package com.example.accountservice.notification.infrastructure

import com.example.accountservice.config.SesProperties
import com.example.accountservice.notification.application.service.NotificationService
import com.example.accountservice.notification.infrastructure.persistence.SentEmail
import com.example.accountservice.notification.infrastructure.persistence.SentEmailJpaRepository
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
        // Level 2 (Ledger) idempotency — if this Outbox event has already resulted in an email being
        // sent, don't resend it. If the SES call succeeds while the relay retries at-least-once, but the
        // process dies before the processed=true commit, this check prevents a duplicate send on the
        // next retry.
        if (sentEmailJpaRepository.existsBySourceEventId(sourceEventId)) {
            logger
                .atInfo()
                .addKeyValue("account_id", accountId)
                .addKeyValue("event_type", eventType)
                .addKeyValue("source_event_id", sourceEventId)
                .log("Event already sent — skipping duplicate send")
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
            .log("Email sent")
    }
}
