package com.example.accountservice.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findByProcessedFalseOrderByCreatedAtAsc();
}
