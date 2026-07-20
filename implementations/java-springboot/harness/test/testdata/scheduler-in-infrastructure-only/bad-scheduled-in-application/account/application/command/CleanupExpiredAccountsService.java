package com.example.accountservice.account.application.command;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CleanupExpiredAccountsService {

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
    }
}
