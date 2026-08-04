package com.example.accountservice.common.web;

import java.time.LocalDateTime;

// Outside the rule's scope (neither domain/ nor persistence/) — a stopwatch reading is
// location-independent and must not be flagged.
public class RequestLoggingInterceptor {
    public long elapsedSeconds(LocalDateTime start) {
        return java.time.Duration.between(start, LocalDateTime.now()).toSeconds();
    }
}
