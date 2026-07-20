package com.example.accountservice.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPoller {

    @Scheduled(fixedDelay = 1000)
    public void poll() {
    }
}
