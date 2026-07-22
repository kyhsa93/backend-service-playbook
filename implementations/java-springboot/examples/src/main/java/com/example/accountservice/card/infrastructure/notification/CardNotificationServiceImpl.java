package com.example.accountservice.card.infrastructure.notification;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.card.application.service.NotificationService;
import com.example.accountservice.card.infrastructure.notification.persistence.CardSentEmail;
import com.example.accountservice.card.infrastructure.notification.persistence.CardSentEmailRepository;
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
 * The AWS SES-based implementation of NotificationService (a Technical Service interface) — the
 * same structure as {@code account/infrastructure/notification/NotificationServiceImpl}. The class
 * is named {@code CardNotificationServiceImpl} for the same reason as {@code
 * PaymentAccountAdapterImpl} — simply naming it {@code NotificationServiceImpl} would collide with
 * the Account BC's identically-named class at the Spring bean level (bean names must be globally
 * unique even across different packages). {@link SesClient} shares the single application-wide bean
 * created by account/infrastructure/notification/SesConfig as-is.
 */
@Component
@RequiredArgsConstructor
public class CardNotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(CardNotificationServiceImpl.class);

    private final SesClient sesClient;
    private final CardSentEmailRepository cardSentEmailRepository;
    private final SesProperties sesProperties;

    // Runs in a separate physical transaction (REQUIRES_NEW) so that a notification-send failure
    // doesn't propagate as rollback-only into the original card batch transaction (same reasoning
    // as account/infrastructure/notification/NotificationServiceImpl).
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmail(
            String cardId, String eventType, String recipient, String subject, String body) {
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

        CardSentEmail sentEmail =
                CardSentEmail.create(cardId, eventType, recipient, subject, response.messageId());
        cardSentEmailRepository.save(sentEmail);

        log.info(
                "Email sent",
                kv("card_id", cardId),
                kv("event_type", eventType),
                kv("recipient", recipient),
                kv("ses_message_id", response.messageId()));
    }
}
