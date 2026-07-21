package com.example.accountservice.taskqueue;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskOutboxJpaRepository extends JpaRepository<TaskOutboxEntry, String> {
    List<TaskOutboxEntry> findByProcessedFalseOrderByCreatedAtAsc();
}
