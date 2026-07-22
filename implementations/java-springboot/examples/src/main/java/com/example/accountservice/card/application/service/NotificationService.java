package com.example.accountservice.card.application.service;

/**
 * A Technical Service interface for sending notifications (email) — the same reasoning and shape as
 * {@code account/application/service/NotificationService}. Because keeping a Technical Service
 * inside the domain that needs it is the default (domain-service.md "placement principle — inside
 * the domain is the default"), the Card BC does not directly reference the Account BC's
 * implementation and instead has its own interface + implementation. The implementation lives in
 * the infrastructure/ layer (card/infrastructure/notification/CardNotificationServiceImpl).
 */
public interface NotificationService {

    void sendEmail(String cardId, String eventType, String recipient, String subject, String body);
}
