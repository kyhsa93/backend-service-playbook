package com.example.accountservice.payment.domain;

/**
 * The shape of a refund reason's classification result — a plain Domain-layer value, not tied to
 * how it was produced. {@link RefundEligibilityService} reads this as one more input alongside
 * {@link Payment}/{@link Refund}; it never imports the Application-layer {@code
 * RefundReasonClassifier} interface that produces it (that would violate domain-layer isolation —
 * the Domain layer must not depend on any other layer). The Technical Service interface
 * (application/service/RefundReasonClassifier.java) imports this type from here instead.
 */
public record RefundReasonClassification(RefundReasonCategory category, double fraudRiskScore) {}
