package com.example.accountservice.card.application.service;

/**
 * 알림(이메일) 발송을 위한 Technical Service 인터페이스 — {@code account/application/service/
 * NotificationService}와 동일한 이유·형태다. Technical Service는 그것을 필요로 하는 도메인 내부에 두는 것이
 * 기본값이므로(domain-service.md "배치 원칙 — 도메인 내부가 기본값이다"), Card BC도 Account BC의 구현체를 직접 참조하지 않고 자신만의
 * 인터페이스+구현체를 갖는다. 구현체는 infrastructure/ 레이어에
 * 위치한다(card/infrastructure/notification/CardNotificationServiceImpl).
 */
public interface NotificationService {

    void sendEmail(String cardId, String eventType, String recipient, String subject, String body);
}
