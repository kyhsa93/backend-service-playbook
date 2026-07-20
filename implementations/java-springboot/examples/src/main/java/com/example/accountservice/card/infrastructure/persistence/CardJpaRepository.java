package com.example.accountservice.card.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardJpaRepository extends JpaRepository<CardJpaEntity, Long> {
    Optional<CardJpaEntity> findByCardId(String cardId); // saveCard()의 update-or-insert 조회용
}
