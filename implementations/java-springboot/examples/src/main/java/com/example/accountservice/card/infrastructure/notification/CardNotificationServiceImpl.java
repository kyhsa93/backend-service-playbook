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
 * NotificationService(Technical Service 인터페이스)의 AWS SES 기반 구현체 — {@code account/infrastructure/
 * notification/NotificationServiceImpl}과 동일한 구조다. 클래스명을 {@code CardNotificationServiceImpl}로 붙인 이유는
 * {@code PaymentAccountAdapterImpl}과 같다 — 단순히 {@code NotificationServiceImpl}로 두면 Account BC의 동명
 * 클래스와 Spring 빈 이름이 충돌한다(패키지가 달라도 빈 이름은 전역으로 유일해야 함). {@link SesClient}는
 * account/infrastructure/notification/SesConfig가 만든 애플리케이션 전역 단일 빈을 그대로 공유한다.
 */
@Component
@RequiredArgsConstructor
public class CardNotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(CardNotificationServiceImpl.class);

    private final SesClient sesClient;
    private final CardSentEmailRepository cardSentEmailRepository;
    private final SesProperties sesProperties;

    // 알림 발송 실패가 원본 카드 배치 트랜잭션까지 rollback-only로 전파되지 않도록 별도의 물리
    // 트랜잭션(REQUIRES_NEW)에서 실행한다(account/infrastructure/notification/NotificationServiceImpl과
    // 동일한 이유).
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
                "이메일 발송됨",
                kv("card_id", cardId),
                kv("event_type", eventType),
                kv("recipient", recipient),
                kv("ses_message_id", response.messageId()));
    }
}
