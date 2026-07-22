package com.example.accountservice.account.application.service;

/**
 * A Technical Service interface for sending notifications (email). It is defined in the shape the
 * caller (the Application layer) needs, and does not expose details of the actual sending
 * technology (SES, etc.). The implementation lives in the infrastructure/ layer
 * (NotificationServiceImpl).
 */
public interface NotificationService {

    void sendEmail(
            String accountId, String eventType, String recipient, String subject, String body);
}
