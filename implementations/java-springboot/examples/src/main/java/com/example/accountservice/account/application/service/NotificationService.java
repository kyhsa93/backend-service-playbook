package com.example.accountservice.account.application.service;

/**
 * 알림(이메일) 발송을 위한 Technical Service 인터페이스. 호출하는 쪽(Application 레이어)이 필요로 하는 형태로 정의하며, 실제 발송 기술(SES
 * 등)에 대한 세부사항은 노출하지 않는다. 구현체는 infrastructure/ 레이어에 위치한다 (NotificationServiceImpl).
 */
public interface NotificationService {

    void sendEmail(
            String accountId, String eventType, String recipient, String subject, String body);
}
