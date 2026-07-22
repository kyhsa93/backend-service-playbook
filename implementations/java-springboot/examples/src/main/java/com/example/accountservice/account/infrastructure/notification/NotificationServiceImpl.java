package com.example.accountservice.account.infrastructure.notification;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmail;
import com.example.accountservice.account.infrastructure.notification.persistence.SentEmailRepository;
import com.example.accountservice.config.SesProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * The AWS SES-based implementation of NotificationService (a Technical Service interface). In this
 * codebase the Service annotation means an Application Service that orchestrates a use case, so
 * this class — a technical implementation in the infrastructure layer — uses the Component
 * annotation instead.
 */
@Component
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final SesClient sesClient;
    private final SentEmailRepository sentEmailRepository;
    private final SesProperties sesProperties;

    // Runs in a separate physical transaction (REQUIRES_NEW) so a notification-send failure does
    // not
    // propagate as rollback-only into the original account command's transaction.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmail(
            String accountId, String eventType, String recipient, String subject, String body) {
        SendEmailRequest request =
                SendEmailRequest.builder()
                        .source(sesProperties.senderEmail())
                        .destination(Destination.builder().toAddresses(recipient).build())
                        .message(
                                Message.builder()
                                        .subject(
                                                Content.builder()
                                                        .data(subject)
                                                        .charset("UTF-8")
                                                        .build())
                                        .body(
                                                Body.builder()
                                                        .text(
                                                                Content.builder()
                                                                        .data(body)
                                                                        .charset("UTF-8")
                                                                        .build())
                                                        .build())
                                        .build())
                        .build();

        SendEmailResponse response = sesClient.sendEmail(request);

        SentEmail sentEmail =
                SentEmail.create(accountId, eventType, recipient, subject, response.messageId());
        sentEmailRepository.save(sentEmail);

        log.info(
                "Email sent",
                kv("account_id", accountId),
                kv("event_type", eventType),
                kv("recipient", recipient),
                kv("ses_message_id", response.messageId()));
    }
}
