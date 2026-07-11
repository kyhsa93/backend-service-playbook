package com.example.accountservice.account.application.service

/**
 * 이메일 발송을 위한 Technical Service 인터페이스.
 *
 * 계좌 도메인 이벤트 리스너 등 Application 레이어의 호출자는 이 인터페이스에만 의존하며,
 * 실제 발송 기술(SES 등)에 대한 구현 상세는 infrastructure 레이어의 구현체가 담당한다.
 * (docs/architecture/domain-service.md의 Technical Service 패턴 참고)
 */
interface NotificationService {
    fun sendEmail(accountId: String, eventType: String, recipient: String, subject: String, body: String)
}
