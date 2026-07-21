package com.example.accountservice.card.application.adapter;

import java.util.Optional;

/**
 * Account BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer). 카드 발급 시 연결 계좌의 존재·활성 여부를 현재 요청 안에서
 * 즉시 확인해야 하므로 동기 Adapter 패턴을 사용한다(cross-domain.md 참고).
 *
 * <p>반환 타입은 Account BC의 {@code AccountStatus} enum을 노출하지 않고 Card BC가 필요로 하는 최소 형태({@link
 * AccountView#active()})로 번역한다 — 상류(Account) 모델 변경이 Card 도메인으로 누수되지 않게 하는 것이 ACL의 목적이다. 실제 번역은
 * infrastructure/AccountAdapterImpl.
 */
public interface AccountAdapter {

    Optional<AccountView> findAccount(String accountId, String ownerId);

    /**
     * Card BC가 소유하는 최소 계좌 뷰 — Account BC의 내부 타입이 Card로 새어들지 않게 한다. {@code email}은 월간 카드 사용내역
     * 안내(scheduling.md Feature 2, {@code SendCardStatementService})가 알림 수신자를 결정하는 데 쓰인다.
     */
    record AccountView(String accountId, boolean active, String email) {}
}
