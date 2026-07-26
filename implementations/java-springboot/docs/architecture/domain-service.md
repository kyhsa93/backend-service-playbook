# Domain Service / Technical Service Pattern (Spring Boot)

> For when a Domain Service is needed, the distinction between Domain Service vs. Application Service vs. Technical Service, and patterns that misuse a Domain Service, see the root [domain-service.md](../../../../docs/architecture/domain-service.md). This document covers the actual Java implementation this repository has.

## Current state of this repository

`examples/` has two kinds of examples.

- **Technical Service**: `account/application/service/NotificationService.java` (interface) + `account/infrastructure/notification/NotificationServiceImpl.java` (implementation, SES). This abstracts technical infrastructure (sending email), but is not domain judgment logic that coordinates multiple Aggregates.
- **Domain Service (genuine cross-Aggregate coordination)**: `payment/domain/RefundEligibilityService.java`. It coordinates a judgment ("the original payment must be in COMPLETED status, the refund amount cannot exceed the payment amount, and an ML-scored fraud-risk signal must not cross a threshold") that can only be made by loading both the `Payment` and `Refund` Aggregates together — a real, working example of the "logic that must read multiple Aggregates to reach a judgment" the root document defines.
- **Technical Service (ML model call)**: `payment/application/service/RefundFraudRiskScorer.java` (interface) + `payment/infrastructure/RefundFraudRiskScorerNativeImpl.java`/`RefundFraudRiskScorerHttpImpl.java` (implementations) — see the dedicated section below.

Since the Account and Card BCs each have only a single Aggregate, this pattern couldn't be demonstrated there — the Payment BC (with its two Aggregates, Payment/Refund) is what actually shows it working.

---

## RefundEligibilityService — an example of cross-Aggregate coordination

The `Payment` Aggregate knows nothing about refund attempts against it (a refund only exists as the separate `Refund` Aggregate). The `Refund` Aggregate knows nothing about the original payment's amount/status (it only references it by `paymentId`). Making this judgment requires loading both Aggregates and comparing them in the same place, so it cannot be placed as a method on either Aggregate alone (doing so would require that Aggregate to take the entire other Aggregate as a parameter, breaking the boundary).

### Step 1 — define it in `domain/` as a pure class with no framework annotations

```java
// payment/domain/RefundEligibilityService.java — actual code
public class RefundEligibilityService {

    // mlFraudRiskScore is a plain value already computed upstream by RefundFraudRiskScorer (a
    // Technical Service trained on the requester's refund/payment history — see the Technical
    // Service section below). This method never calls it and doesn't know an ML model produced
    // the value; it only weighs the fraud-risk signal alongside its other checks and still owns
    // the actual judgment.
    private static final double ML_FRAUD_RISK_REJECTION_THRESHOLD = 0.8;

    public RefundDecision evaluate(Payment payment, Refund refund, double mlFraudRiskScore) {
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_REQUIRES_COMPLETED_PAYMENT,
                    "A refund can only be requested for a completed payment.");
        }
        if (refund.getAmount() > payment.getAmount()) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT,
                    "The refund amount cannot exceed the payment amount.");
        }
        if (mlFraudRiskScore >= ML_FRAUD_RISK_REJECTION_THRESHOLD) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_PATTERN_FLAGGED_HIGH_RISK,
                    "This refund pattern was flagged as high risk by the fraud-risk model and requires manual review.");
        }
        return RefundDecision.approve();
    }
}
```

```java
// payment/domain/RefundDecision.java — the judgment result. Even if rejected, this is returned as a value, not thrown as an exception.
public record RefundDecision(boolean approved, PaymentException.ErrorCode code, String reason) {
    public static RefundDecision approve() { return new RefundDecision(true, null, null); }
    public static RefundDecision rejected(PaymentException.ErrorCode code, String reason) {
        return new RefundDecision(false, code, reason);
    }
}
```

There is no stereotype annotation of any kind (`@Service`/`@Component`) — it is never registered as a Spring bean.

### Step 2 — the Application Service loads both Repositories and delegates

```java
// payment/application/command/RequestRefundService.java — actual code (excerpt)
@Service
@RequiredArgsConstructor
public class RequestRefundService {

    // Stateless pure judgment logic, so it's instantiated directly rather than via Spring DI — reusing it across requests is safe.
    private final RefundEligibilityService refundEligibilityService = new RefundEligibilityService();

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    // RefundFraudRiskScorer is a Technical Service (DI-bound to its real implementation) — unlike
    // RefundEligibilityService above, it wraps external I/O (an ML model), so it's injected rather
    // than `new`'d directly.
    private final RefundFraudRiskScorer refundFraudRiskScorer;

    public GetRefundResult request(RequestRefundCommand command) {
        Payment payment = /* loaded via paymentRepository.findPayments(...) after verifying ownership */;
        Refund refund = Refund.create(payment.getPaymentId(), command.amount(), command.reason());
        double mlFraudRiskScore = refundFraudRiskScorer.score(/* refund/payment history features, see RefundRiskFeatures */);

        RefundDecision decision = refundEligibilityService.evaluate(payment, refund, mlFraudRiskScore);
        if (decision.approved()) {
            refund.approve(payment.getAccountId(), payment.getOwnerId());
        } else {
            refund.reject(decision.reason());
        }

        refundRepository.saveRefund(refund);   // @Transactional — Refund save + Outbox write, one transaction (see persistence.md)
        return GetRefundResult.from(refund);   // returned right after saving — draining is handled asynchronously by OutboxPoller/OutboxConsumer (see domain-events.md)
    }
}
```

- `RefundEligibilityService` is kept as a field, but it's initialized directly with `new` rather than being a target of `@RequiredArgsConstructor` (Lombok constructor injection) — in contrast to the other fields (`PaymentRepository`, etc., collaborators Spring injects).
- A refund rejection (`decision.approved() == false`) is a valid domain conclusion, not an exception — `RequestRefundService.request()` doesn't throw in this case either, and simply returns the `Refund` saved in `REJECTED` status. `PaymentController` responds to this not as an error but as `201 Created` + `status: "REJECTED"` (a design decision made by Payment BC — see `interfaces/rest/PaymentController.java`).

### RefundDecision and PaymentException.ErrorCode — assigning error codes even to judgment results

This repository's [error-handling.md](error-handling.md) requires "1 guard condition = 1 `ErrorCode`." A refund rejection is not thrown as an exception, but so that the exact rule that produced the rejection can be traced, `RefundDecision` carries a `PaymentException.ErrorCode` as data (`REFUND_REQUIRES_COMPLETED_PAYMENT`/`REFUND_AMOUNT_EXCEEDS_PAYMENT`) — one step more typed than the nestjs reference implementation, which returns only a message string.

### Related code

- `payment/domain/Payment.java`, `Refund.java`, `RefundEligibilityService.java`, `RefundDecision.java`, `PaymentException.java`
- `payment/application/command/RequestRefundService.java` — the call site of the Domain Service
- `payment/domain/RefundEligibilityServiceTest.java` — a unit test that instantiates it directly with `new`, without a Spring context, verifying only the judgment logic (no ML call — the score is always passed in as a plain value)
- `payment/interfaces/rest/PaymentControllerE2ETest.java` — verifies both the refund-approval and refund-rejection paths via the actual HTTP API

---

## Technical Service — NotificationService

The Technical Service placement principle (domain-internal by default, per YAGNI) and the `NotificationService`/`NotificationServiceImpl` code are already covered repeatedly by [file-storage.md](file-storage.md)/[secret-manager.md](secret-manager.md)/[directory-structure.md](directory-structure.md), so they aren't duplicated here.

---

## Technical Service — RefundFraudRiskScorer (an ML model)

A real, working example of the Technical Service pattern for an ML model call. The interface is defined in the shape the Domain Service needs (a single `double` score), and the implementation is free to swap between an in-process model and a shared microservice without the Application or Domain layer ever changing:

```java
// payment/application/service/RefundFraudRiskScorer.java — the interface
public interface RefundFraudRiskScorer {
    double score(RefundRiskFeatures features);
}
```

Two implementations exist side by side, selected by `config/FraudScorerProperties` (`fraud-scorer.mode`, `native`/`http`): `payment/infrastructure/RefundFraudRiskScorerNativeImpl.java` trains a small model in-process at startup from the requester's refund/payment history (refund count, rejection count, amount ratio, minutes since payment — see `RefundRiskFeatures`), and `payment/infrastructure/RefundFraudRiskScorerHttpImpl.java` calls the shared `services/fraud-risk-scorer` microservice over HTTP instead. Both fall back to a neutral score (`0`) on any failure — a scoring outage must never block a refund request, so the failure is swallowed at this Infrastructure boundary rather than surfaced as a domain error. Its unit test (`RequestRefundServiceTest`) mocks the interface rather than hitting a real model — no external dependency, no non-determinism, in the test.

Because the interface is defined in the shape the Domain Service needs rather than around a specific model's API, which implementation is wired up is purely a config choice, never a Domain concern. Neither implementation depends on user-supplied free text — every input feature is a structured fact drawn from the requester's own refund/payment history, which the requester cannot simply type something different to fake.

### Related code

- `payment/application/service/RefundFraudRiskScorer.java`, `payment/infrastructure/RefundFraudRiskScorerNativeImpl.java`, `payment/infrastructure/RefundFraudRiskScorerHttpImpl.java`
- `config/FraudScorerProperties.java` — the `native`/`http` mode switch, via `@ConfigurationProperties`
- `payment/application/command/RequestRefundServiceTest.java` — mocks `RefundFraudRiskScorer`

---

## Harness verification

`harness/src/rules/NoCrossAggregateReference.java` (rule: `no-cross-aggregate-reference`) checks that `payment/domain/Payment.java` never references the `Refund` type directly as a field/parameter, and that `payment/domain/Refund.java` never references `Payment` directly — the two Aggregates must only reference each other via an ID string like `paymentId` (see the `RefundEligibilityService` explanation above), and coordination logic must only ever go in a Domain Service. This rule is currently scoped to the one real case in this repository where a single BC has more than one Aggregate (the Payment BC).

---

### Related documents

- [domain-service.md (root)](../../../../docs/architecture/domain-service.md) — the framework-agnostic principles
- [tactical-ddd.md](tactical-ddd.md) — Aggregate design
- [cross-domain.md](cross-domain.md) — the Adapter pattern (its difference from Technical Service/Domain Service)
- [error-handling.md](error-handling.md) — the 1:1 `ErrorCode` mapping principle
