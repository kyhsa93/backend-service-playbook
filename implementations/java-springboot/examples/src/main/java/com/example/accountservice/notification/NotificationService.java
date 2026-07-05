package com.example.accountservice.notification;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SesClient sesClient;
    private final SentEmailRepository sentEmailRepository;

    @Value("${ses.sender-email:no-reply@backend-service-playbook.example.com}")
    private String senderEmail;

    // 알림 발송 실패가 원본 계좌 커맨드의 트랜잭션까지 rollback-only로 전파되지 않도록
    // 별도의 물리 트랜잭션(REQUIRES_NEW)에서 실행한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmail(String accountId, String eventType, String recipient, String subject, String body) {
        SendEmailRequest request = SendEmailRequest.builder()
                .source(senderEmail)
                .destination(Destination.builder().toAddresses(recipient).build())
                .message(Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder()
                                .text(Content.builder().data(body).charset("UTF-8").build())
                                .build())
                        .build())
                .build();

        SendEmailResponse response = sesClient.sendEmail(request);

        SentEmail sentEmail = SentEmail.create(accountId, eventType, recipient, subject, response.messageId());
        sentEmailRepository.save(sentEmail);

        log.info("이메일 발송됨: accountId={}, eventType={}, recipient={}, sesMessageId={}",
                accountId, eventType, recipient, response.messageId());
    }
}
