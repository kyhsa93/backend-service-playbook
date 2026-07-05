package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.Transaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {

    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final EntityManager em;

    @Override
    public Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId) {
        return jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId);
    }

    @Override
    public List<Account> findAll(AccountFindQuery query) {
        String jpql = buildJpql(query, false);
        var q = em.createQuery(jpql, Account.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(q, query);
        return q.getResultList();
    }

    @Override
    public long countAll(AccountFindQuery query) {
        String jpql = buildJpql(query, true);
        var q = em.createQuery(jpql, Long.class);
        applyParams(q, query);
        return q.getSingleResult();
    }

    @Override
    public void save(Account account) {
        jpaRepository.save(account);
        List<Transaction> pending = account.pullPendingTransactions();
        if (!pending.isEmpty()) {
            transactionJpaRepository.saveAll(pending);
        }
    }

    @Override
    public List<Transaction> findTransactions(String accountId, int page, int take) {
        return transactionJpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, take));
    }

    @Override
    public long countTransactions(String accountId) {
        return transactionJpaRepository.countByAccountId(accountId);
    }

    private String buildJpql(AccountFindQuery query, boolean count) {
        StringBuilder sb = new StringBuilder(count
                ? "SELECT COUNT(a) FROM Account a WHERE a.deletedAt IS NULL"
                : "SELECT a FROM Account a WHERE a.deletedAt IS NULL");
        if (query.accountId() != null && !query.accountId().isBlank()) sb.append(" AND a.accountId = :accountId");
        if (query.ownerId() != null && !query.ownerId().isBlank()) sb.append(" AND a.ownerId = :ownerId");
        if (query.status() != null && !query.status().isEmpty()) sb.append(" AND a.status IN :status");
        if (!count) sb.append(" ORDER BY a.accountId DESC");
        return sb.toString();
    }

    private void applyParams(Query q, AccountFindQuery query) {
        if (query.accountId() != null && !query.accountId().isBlank()) q.setParameter("accountId", query.accountId());
        if (query.ownerId() != null && !query.ownerId().isBlank()) q.setParameter("ownerId", query.ownerId());
        if (query.status() != null && !query.status().isEmpty()) {
            q.setParameter("status", query.status().stream().map(AccountStatus::valueOf).toList());
        }
    }
}
