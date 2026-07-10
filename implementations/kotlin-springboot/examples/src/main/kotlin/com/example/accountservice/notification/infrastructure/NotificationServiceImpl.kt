package com.example.accountservice.notification.infrastructure

import com.example.accountservice.notification.application.service.NotificationService
import com.example.accountservice.notification.infrastructure.persistence.SentEmail
import com.example.accountservice.notification.infrastructure.persistence.SentEmailJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${SES_SENDER_EMAIL:no-reply@backend-service-playbook.example.com}") private val senderEmail: String,
) : NotificationService {
    private val logger = LoggerFactory.getLogger(NotificationServiceImpl::class.java)

    override fun sendEmail(accountId: String, eventType: String, recipient: String, subject: String, body: String) {
        val request = SendEmailRequest.builder()
            .source(senderEmail)
            .destination(Destination.builder().toAddresses(recipient).build())
            .message(
                Message.builder()
                    .subject(Content.builder().data(subject).charset("UTF-8").build())
                    .body(Body.builder().text(Content.builder().data(body).charset("UTF-8").build()).build())
                    .build(),
            )
            .build()

        val result = sesClient.sendEmail(request)

        sentEmailJpaRepository.save(
            SentEmail.create(
                accountId = accountId,
                eventType = eventType,
                recipient = recipient,
                subject = subject,
                sesMessageId = result.messageId(),
            ),
        )

        logger.atInfo()
            .addKeyValue("account_id", accountId)
            .addKeyValue("event_type", eventType)
            .addKeyValue("recipient", recipient)
            .addKeyValue("ses_message_id", result.messageId())
            .log("이메일 발송됨")
    }
}
