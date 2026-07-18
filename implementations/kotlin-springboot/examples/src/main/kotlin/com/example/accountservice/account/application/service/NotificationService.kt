package com.example.accountservice.account.application.service

/**
 * 이메일 발송을 위한 Technical Service 인터페이스.
 *
 * 계좌 도메인 이벤트 리스너 등 Application 레이어의 호출자는 이 인터페이스에만 의존하며,
 * 실제 발송 기술(SES 등)에 대한 구현 상세는 infrastructure 레이어의 구현체가 담당한다.
 * (docs/architecture/domain-service.md의 Technical Service 패턴 참고)
 *
 * [sourceEventId]는 이 발송을 촉발한 Outbox 행의 `eventId`다 — 구현체가 이를 키로 중복 발송을
 * 막는 Level 2(Ledger) 멱등성을 적용한다(domain-events.md "이벤트 핸들러 멱등성" 참고).
 */
interface NotificationService {
    fun sendEmail(
        accountId: String,
        eventType: String,
        sourceEventId: String,
        recipient: String,
        subject: String,
        body: String,
    )
}
