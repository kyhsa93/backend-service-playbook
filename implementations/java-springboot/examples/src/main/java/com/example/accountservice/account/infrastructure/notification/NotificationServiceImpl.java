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
 * NotificationService(Technical Service 인터페이스)의 AWS SES 기반 구현체. 이 코드베이스에서 Service 애노테이션은 유스케이스를
 * 조율하는 Application Service를 뜻하므로, infrastructure 레이어의 기술 구현체인 이 클래스는 그 대신 Component 애노테이션을 사용한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final SesClient sesClient;
    private final SentEmailRepository sentEmailRepository;
    private final SesProperties sesProperties;

    // 알림 발송 실패가 원본 계좌 커맨드의 트랜잭션까지 rollback-only로 전파되지 않도록
    // 별도의 물리 트랜잭션(REQUIRES_NEW)에서 실행한다.
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
                "이메일 발송됨",
                kv("account_id", accountId),
                kv("event_type", eventType),
                kv("recipient", recipient),
                kv("ses_message_id", response.messageId()));
    }
}
