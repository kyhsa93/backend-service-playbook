package com.example.accountservice.account.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepositoryImpl {

    private final EntityManager em;

    public AccountRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    public Object findAccounts(Object query) {
        String jpql = buildJpql();
        return em.createQuery(jpql, AccountJpaEntity.class).getResultList();
    }

    private String buildJpql() {
        return "SELECT a FROM AccountJpaEntity a";
    }
}
