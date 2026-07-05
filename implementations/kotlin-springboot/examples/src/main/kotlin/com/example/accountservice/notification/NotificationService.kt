package com.example.accountservice.notification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.Body
import software.amazon.awssdk.services.ses.model.Content
import software.amazon.awssdk.services.ses.model.Destination
import software.amazon.awssdk.services.ses.model.Message
import software.amazon.awssdk.services.ses.model.SendEmailRequest

@Service
class NotificationService(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    @Value("\${SES_SENDER_EMAIL:no-reply@backend-service-playbook.example.com}") private val senderEmail: String,
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    fun sendEmail(accountId: String, eventType: String, recipient: String, subject: String, body: String) {
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

        logger.info(
            "이메일 발송됨: accountId={}, eventType={}, recipient={}, sesMessageId={}",
            accountId,
            eventType,
            recipient,
            result.messageId(),
        )
    }
}
