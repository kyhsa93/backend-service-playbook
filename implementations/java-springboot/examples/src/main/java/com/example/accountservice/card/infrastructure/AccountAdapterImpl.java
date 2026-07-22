package com.example.accountservice.card.infrastructure;

import com.example.accountservice.account.application.query.AccountQuery;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.card.application.adapter.AccountAdapter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The implementation of {@link AccountAdapter} (ACL). Injects and calls the read interface exposed
 * by the Account BC ({@link AccountQuery}), and translates the Account BC's model into the minimal
 * shape the Card BC uses ({@link AccountAdapter.AccountView}). This is the sole boundary point that
 * keeps Account's Repository/domain objects from being exposed to Card's Application/Domain layers.
 *
 * <p>Since Account's read interface represents "account not found" as {@code Optional.empty} rather
 * than an exception, that signal is translated as-is into the {@code Optional.empty} that Card
 * understands — Account's exception types never leak into the Card domain.
 */
@Component
@RequiredArgsConstructor
public class AccountAdapterImpl implements AccountAdapter {

    private final AccountQuery accountQuery;

    @Override
    public Optional<AccountView> findAccount(String accountId, String ownerId) {
        return accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, ownerId, null))
                .accounts()
                .stream()
                .findFirst()
                .map(
                        account ->
                                new AccountView(
                                        account.getAccountId(),
                                        account.getStatus() == AccountStatus.ACTIVE,
                                        account.getEmail()));
    }
}
