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
 * The implementation (ACL) of {@link AccountAdapter}. Injects and calls the read interface ({@link
 * AccountQuery}) exposed by the Account BC, and translates the Account BC's model into the minimal
 * shape the Payment BC uses ({@link AccountAdapter.AccountView}). Unlike Card BC's
 * AccountAdapterImpl, this also needs to translate the balance (balanceAmount), since the balance
 * is needed to judge whether a payment can proceed.
 */
// Why the class is named PaymentAccountAdapterImpl: Spring's default bean name is derived from
// the simple class name, so if it shared a name with card/infrastructure/AccountAdapterImpl
// (owned by Card BC), component scanning would throw ConflictingBeanDefinitionException — bean
// names must be globally unique even across different packages.
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
