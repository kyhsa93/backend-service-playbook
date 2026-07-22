package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.application.query.AccountQuery;
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.account.domain.TransactionType;
import com.example.accountservice.account.domain.TransactionsWithCount;
import com.example.accountservice.outbox.OutboxWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {

    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        String listJpql = buildJpql(query, false);
        var listQuery =
                em.createQuery(listJpql, AccountJpaEntity.class)
                        .setFirstResult(query.page() * query.take())
                        .setMaxResults(query.take());
        applyParams(listQuery, query);
        List<Account> accounts =
                listQuery.getResultList().stream().map(AccountMapper::toDomain).toList();

        String countJpql = buildJpql(query, true);
        var countQuery = em.createQuery(countJpql, Long.class);
        applyParams(countQuery, query);
        long count = countQuery.getSingleResult();

        return new AccountsWithCount(accounts, count);
    }

    @Override
    @Transactional
    public void saveAccount(Account account) {
        saveAccountInternal(account);
    }

    /**
     * Bundles the source/target Account saves into a single physical transaction — Transfer
     * (transfer between accounts) is the first use case for this. If the withdrawal-account save
     * and the deposit-account save were each committed as separate transactions, a failure mode
     * would arise where "the withdrawal is applied but the deposit is lost."
     */
    @Override
    @Transactional
    public void saveAccounts(Account source, Account target) {
        saveAccountInternal(source);
        saveAccountInternal(target);
    }

    private void saveAccountInternal(Account account) {
        AccountJpaEntity entity =
                jpaRepository
                        .findByAccountId(account.getAccountId())
                        .map(existing -> AccountMapper.updateEntity(existing, account))
                        .orElseGet(() -> AccountMapper.toNewEntity(account));
        jpaRepository.save(entity);
        List<Transaction> pending = account.pullPendingTransactions();
        if (!pending.isEmpty()) {
            transactionJpaRepository.saveAll(
                    pending.stream().map(TransactionMapper::toNewEntity).toList());
        }
        // Records the event to the Outbox within the same physical transaction as the Aggregate
        // save
        // (see domain-events.md). If this method returns without an exception, the
        // Account/Transaction/
        // Outbox rows are all committed together, or all rolled back together.
        outboxWriter.saveAll(account.pullDomainEvents());
    }

    @Override
    @Transactional
    public void deleteAccount(String accountId) {
        jpaRepository
                .findByAccountIdAndDeletedAtIsNull(accountId)
                .ifPresent(
                        entity -> {
                            Account account = AccountMapper.toDomain(entity);
                            account.delete(); // Validates the invariant (only a CLOSED account may
                            // be deleted) via the domain method, then sets
                            // deletedAt
                            AccountMapper.updateEntity(entity, account);
                            jpaRepository.save(entity);
                        });
    }

    @Override
    public boolean hasTransactionWithReference(String referenceId, TransactionType type) {
        return transactionJpaRepository.existsByReferenceIdAndType(referenceId, type);
    }

    @Override
    public TransactionsWithCount findTransactions(String accountId, int page, int take) {
        List<Transaction> transactions =
                transactionJpaRepository
                        .findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, take))
                        .stream()
                        .map(TransactionMapper::toDomain)
                        .toList();
        long count = transactionJpaRepository.countByAccountId(accountId);
        return new TransactionsWithCount(transactions, count);
    }

    private String buildJpql(AccountFindQuery query, boolean count) {
        StringBuilder sb =
                new StringBuilder(
                        count
                                ? "SELECT COUNT(a) FROM AccountJpaEntity a WHERE a.deletedAt IS NULL"
                                : "SELECT a FROM AccountJpaEntity a WHERE a.deletedAt IS NULL");
        if (query.accountId() != null && !query.accountId().isBlank())
            sb.append(" AND a.accountId = :accountId");
        if (query.ownerId() != null && !query.ownerId().isBlank())
            sb.append(" AND a.ownerId = :ownerId");
        if (query.status() != null && !query.status().isEmpty())
            sb.append(" AND a.status IN :status");
        if (!count) sb.append(" ORDER BY a.accountId DESC");
        return sb.toString();
    }

    private void applyParams(Query q, AccountFindQuery query) {
        if (query.accountId() != null && !query.accountId().isBlank())
            q.setParameter("accountId", query.accountId());
        if (query.ownerId() != null && !query.ownerId().isBlank())
            q.setParameter("ownerId", query.ownerId());
        if (query.status() != null && !query.status().isEmpty()) {
            q.setParameter("status", query.status().stream().map(AccountStatus::valueOf).toList());
        }
    }
}
