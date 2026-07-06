package com.example.accountservice.account.application.event;

import com.example.accountservice.account.domain.AccountClosedEvent;
import com.example.accountservice.account.domain.AccountCreatedEvent;
import com.example.accountservice.account.domain.AccountReactivatedEvent;
import com.example.accountservice.account.domain.AccountSuspendedEvent;
import com.example.accountservice.account.domain.MoneyDepositedEvent;
import com.example.accountservice.account.domain.MoneyWithdrawnEvent;
import com.example.accountservice.notification.application.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Account 도메인 이벤트를 구독해 계좌 소유자에게 알림 이메일을 발송한다.
 * ApplicationEventPublisher.publishEvent()는 발행자와 같은 호출 스택에서 동기적으로 리스너를 실행하므로,
 * 알림 발송 실패가 원본 계좌 커맨드에 영향을 주지 않도록 각 처리를 개별적으로 catch한다.
 */
@Component
@RequiredArgsConstructor
public class AccountNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(AccountNotificationListener.class);

    private final NotificationService notificationService;

    @EventListener
    public void on(AccountCreatedEvent event) {
        send(event.accountId(), "AccountCreated", event.email(),
                "[Account] 계좌가 개설되었습니다",
                "계좌(" + event.accountId() + ")가 개설되었습니다. 통화: " + event.currency());
    }

    @EventListener
    public void on(MoneyDepositedEvent event) {
        send(event.accountId(), "MoneyDeposited", event.email(),
                "[Account] 입금이 완료되었습니다",
                event.amount().amount() + " " + event.amount().currency() + "이 입금되었습니다. 입금 후 잔액: "
                        + event.balanceAfter().amount() + " " + event.balanceAfter().currency());
    }

    @EventListener
    public void on(MoneyWithdrawnEvent event) {
        send(event.accountId(), "MoneyWithdrawn", event.email(),
                "[Account] 출금이 완료되었습니다",
                event.amount().amount() + " " + event.amount().currency() + "이 출금되었습니다. 출금 후 잔액: "
                        + event.balanceAfter().amount() + " " + event.balanceAfter().currency());
    }

    @EventListener
    public void on(AccountSuspendedEvent event) {
        send(event.accountId(), "AccountSuspended", event.email(),
                "[Account] 계좌가 정지되었습니다",
                "계좌(" + event.accountId() + ")가 정지되었습니다.");
    }

    @EventListener
    public void on(AccountReactivatedEvent event) {
        send(event.accountId(), "AccountReactivated", event.email(),
                "[Account] 계좌가 재개되었습니다",
                "계좌(" + event.accountId() + ")가 재개되었습니다.");
    }

    @EventListener
    public void on(AccountClosedEvent event) {
        send(event.accountId(), "AccountClosed", event.email(),
                "[Account] 계좌가 종료되었습니다",
                "계좌(" + event.accountId() + ")가 종료되었습니다.");
    }

    private void send(String accountId, String eventType, String recipient, String subject, String body) {
        try {
            notificationService.sendEmail(accountId, eventType, recipient, subject, body);
        } catch (Exception e) {
            log.error("알림 이메일 발송 실패: accountId={}, eventType={}", accountId, eventType, e);
        }
    }
}
