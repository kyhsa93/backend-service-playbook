package com.example.accountservice.taskqueue

import org.springframework.data.jpa.repository.JpaRepository

interface TaskOutboxJpaRepository : JpaRepository<TaskOutbox, Long> {
    fun findByProcessedFalseOrderByCreatedAtAsc(): List<TaskOutbox>
}
