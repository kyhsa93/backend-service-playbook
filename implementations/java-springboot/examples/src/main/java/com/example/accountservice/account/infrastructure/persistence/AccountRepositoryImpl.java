package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.application.query.AccountQueryRepository;
import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.account.domain.Transaction;
import com.example.accountservice.outbox.OutboxWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQueryRepository {

    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    @Override
    public Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId) {
        return jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId)
                .map(AccountMapper::toDomain);
    }

    @Override
    public List<Account> findAll(AccountFindQuery query) {
        String jpql = buildJpql(query, false);
        var q = em.createQuery(jpql, AccountJpaEntity.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(q, query);
        return q.getResultList().stream().map(AccountMapper::toDomain).toList();
    }

    @Override
    public long countAll(AccountFindQuery query) {
        String jpql = buildJpql(query, true);
        var q = em.createQuery(jpql, Long.class);
        applyParams(q, query);
        return q.getSingleResult();
    }

    @Override
    @Transactional
    public void save(Account account) {
        AccountJpaEntity entity = jpaRepository.findByAccountId(account.getAccountId())
                .map(existing -> AccountMapper.updateEntity(existing, account))
                .orElseGet(() -> AccountMapper.toNewEntity(account));
        jpaRepository.save(entity);
        List<Transaction> pending = account.pullPendingTransactions();
        if (!pending.isEmpty()) {
            transactionJpaRepository.saveAll(pending.stream().map(TransactionMapper::toNewEntity).toList());
        }
        // Aggregate 저장과 같은 물리 트랜잭션 안에서 Outbox에 이벤트를 기록한다(domain-events.md 참고).
        // 이 메서드가 예외 없이 반환되면 Account/Transaction/Outbox 행이 모두 커밋되거나 함께 롤백된다.
        outboxWriter.saveAll(account.pullDomainEvents());
    }

    @Override
    @Transactional
    public void delete(String accountId) {
        jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId).ifPresent(entity -> {
            Account account = AccountMapper.toDomain(entity);
            account.delete(); // 도메인 메서드로 불변식(CLOSED 상태만 삭제 가능) 검증 후 deletedAt 설정
            AccountMapper.updateEntity(entity, account);
            jpaRepository.save(entity);
        });
    }

    @Override
    public List<Transaction> findTransactions(String accountId, int page, int take) {
        return transactionJpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, take))
                .stream().map(TransactionMapper::toDomain).toList();
    }

    @Override
    public long countTransactions(String accountId) {
        return transactionJpaRepository.countByAccountId(accountId);
    }

    private String buildJpql(AccountFindQuery query, boolean count) {
        StringBuilder sb = new StringBuilder(count
                ? "SELECT COUNT(a) FROM AccountJpaEntity a WHERE a.deletedAt IS NULL"
                : "SELECT a FROM AccountJpaEntity a WHERE a.deletedAt IS NULL");
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
