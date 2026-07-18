package com.example.accountservice.payment.infrastructure;

import com.example.accountservice.account.application.query.AccountQuery;
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.payment.application.adapter.AccountAdapter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link AccountAdapter}의 구현체(ACL). Account BC가 노출한 읽기 인터페이스({@link AccountQuery})를 주입받아 호출하고,
 * Account BC의 모델을 Payment BC가 쓰는 최소 형태({@link AccountAdapter.AccountView})로 번역한다. Card BC의
 * AccountAdapterImpl과 달리 잔액(balanceAmount)까지 번역이 필요하다(결제 가능 여부 판단에 잔액이 필요하기 때문).
 */
// 클래스명이 PaymentAccountAdapterImpl인 이유: Spring의 기본 빈 이름은 단순 클래스명에서 유도되므로
// card/infrastructure/AccountAdapterImpl(Card BC 소유)과 이름이 같으면 component scanning 시
// ConflictingBeanDefinitionException이 발생한다 — 패키지가 달라도 빈 이름은 전역으로 유일해야 한다.
@Component
@RequiredArgsConstructor
public class PaymentAccountAdapterImpl implements AccountAdapter {

    private final AccountQuery accountQuery;

    @Override
    public Optional<AccountView> findAccount(String accountId, String ownerId) {
        return accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, ownerId, null))
                .accounts()
                .stream()
                .findFirst()
                .map(this::toAccountView);
    }

    private AccountView toAccountView(Account account) {
        return new AccountView(
                account.getAccountId(),
                account.getStatus() == AccountStatus.ACTIVE,
                account.getBalance().amount(),
                account.getBalance().currency());
    }
}
