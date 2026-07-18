package com.example.accountservice.payment.application.adapter;

import java.util.Optional;

/**
 * Account BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer). 결제 가능 여부(계좌 활성 여부 + 잔액 충분 여부)를 현재 요청
 * 안에서 즉시 확인해야 하므로 동기 Adapter 패턴을 사용한다. 실제 차감은 이 동기 조회의 몫이 아니다 — payment.completed.v1 Integration
 * Event를 Account BC가 구독해 비동기로 수행한다(cross-domain.md의 "동기=조회, 비동기 Integration Event=상태변경" 원칙).
 */
public interface AccountAdapter {

    Optional<AccountView> findAccount(String accountId, String ownerId);

    /**
     * Payment BC가 소유하는 최소 계좌 뷰 — Card BC의 AccountAdapter.AccountView와 달리 잔액 판단(balanceAmount)까지
     * 필요하다.
     */
    record AccountView(String accountId, boolean active, long balanceAmount, String currency) {}
}
