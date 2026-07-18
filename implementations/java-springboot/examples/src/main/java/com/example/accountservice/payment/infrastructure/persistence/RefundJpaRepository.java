package com.example.accountservice.payment.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<RefundJpaEntity, Long> {
    Optional<RefundJpaEntity> findByRefundId(String refundId);
}
