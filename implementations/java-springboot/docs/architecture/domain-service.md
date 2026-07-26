# Domain Service / Technical Service Pattern (Spring Boot)

> For when a Domain Service is needed, the distinction between Domain Service vs. Application Service vs. Technical Service, and patterns that misuse a Domain Service, see the root [domain-service.md](../../../../docs/architecture/domain-service.md). This document covers the actual Java implementation this repository has.

## Current state of this repository

`examples/` has two kinds of examples.

- **Technical Service**: `account/application/service/NotificationService.java` (interface) + `account/infrastructure/notification/NotificationServiceImpl.java` (implementation, SES). This abstracts technical infrastructure (sending email), but is not domain judgment logic that coordinates multiple Aggregates.
- **Domain Service (genuine cross-Aggregate coordination)**: `payment/domain/RefundEligibilityService.java`. It coordinates a judgment ("the original payment must be in COMPLETED status, and the refund amount cannot exceed the payment amount") that can only be made by loading both the `Payment` and `Refund` Aggregates together — a real, working example of the "logic that must read multiple Aggregates to reach a judgment" the root document defines. It carries no fraud-risk judgment of any kind — coordinating the two Aggregates for this amount/status check is the only reason this class exists, which is exactly the point: a Domain Service doesn't need an ML signal or any other extra complexity to be legitimate.
- **Technical Service pair (LLM calls) + the Query Service orchestrating them**: `account/application/service/NlTransactionQueryTranslator.java`/`NlTransactionAnswerComposer.java` (interfaces) + `account/infrastructure/NlTransactionQueryTranslatorImpl.java`/`NlTransactionAnswerComposerImpl.java` (implementations, Ollama), orchestrated by `account/application/query/AskTransactionHistoryService.java` — a real, working structured-data RAG pipeline. See the dedicated section below.

Since the Account and Card BCs each have only a single Aggregate, this pattern couldn't be demonstrated there — the Payment BC (with its two Aggregates, Payment/Refund) is what actually shows it working.

---

## RefundEligibilityService — an example of cross-Aggregate coordination

The `Payment` Aggregate knows nothing about refund attempts against it (a refund only exists as the separate `Refund` Aggregate). The `Refund` Aggregate knows nothing about the original payment's amount/status (it only references it by `paymentId`). Making this judgment requires loading both Aggregates and comparing them in the same place, so it cannot be placed as a method on either Aggregate alone (doing so would require that Aggregate to take the entire other Aggregate as a parameter, breaking the boundary).

### Step 1 — define it in `domain/` as a pure class with no framework annotations

```java
// payment/domain/RefundEligibilityService.java — actual code
public class RefundEligibilityService {

    public RefundDecision evaluate(Payment payment, Refund refund) {
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

    public GetRefundResult request(RequestRefundCommand command) {
        Payment payment = /* loaded via paymentRepository.findPayments(...) after verifying ownership */;
        Refund refund = Refund.create(payment.getPaymentId(), command.amount(), command.reason());

        RefundDecision decision = refundEligibilityService.evaluate(payment, refund);
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
- `payment/domain/RefundEligibilityServiceTest.java` — a unit test that instantiates it directly with `new`, without a Spring context, verifying only the judgment logic
- `payment/interfaces/rest/PaymentControllerE2ETest.java` — verifies both the refund-approval and refund-rejection paths via the actual HTTP API

---

## Technical Service — NotificationService

The Technical Service placement principle (domain-internal by default, per YAGNI) and the `NotificationService`/`NotificationServiceImpl` code are already covered repeatedly by [file-storage.md](file-storage.md)/[secret-manager.md](secret-manager.md)/[directory-structure.md](directory-structure.md), so they aren't duplicated here.

---

## A real, working example — a structured-data RAG pipeline (two LLM Technical Services + a Query Service orchestrating them)

`AskTransactionHistoryService` (Account BC) answers a free-text question about an account's transaction history — e.g. "How much did I deposit this month?" — using two LLM-backed Technical Services either side of an ordinary Repository read, following the Retrieve → Augment → Generate shape of RAG (here, "Retrieve" is a structured DB query, not a vector-embedding search — commonly called "structured-data RAG" or "RAG over a database" to distinguish it from the canonical vector-search form):

1. **Retrieve-preparation** — `NlTransactionQueryTranslator` (a Technical Service, LLM call) turns the question into a structured filter (`type`/`fromDate`/`toDate`).
2. **Retrieve** — `AccountQuery.findTransactions` runs that filter, scoped to the account (an ordinary Query, no LLM involved).
3. **Generate** — `NlTransactionAnswerComposer` (a second Technical Service, LLM call) answers the question, grounded only in the retrieved records.

```java
// application/service/TransactionFilter.java — the interface's return shape
public record TransactionFilter(TransactionType type, LocalDate fromDate, LocalDate toDate) {}

// application/service/NlTransactionQueryTranslator.java — the interface
public interface NlTransactionQueryTranslator {
    TransactionFilter translate(String question);
}

// application/service/NlTransactionAnswerComposer.java — the interface
public interface NlTransactionAnswerComposer {
    String compose(String question, List<GetTransactionsResult.TransactionSummary> transactions);
}
```

All orchestration lives in the Query Service (the Application layer) — never in the Controller, which only wraps the HTTP request into DTOs and dispatches here:

```java
// application/query/AskTransactionHistoryService.java — actual code (excerpt)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AskTransactionHistoryService {

    private static final int MAX_TRANSACTIONS_FOR_ANSWER = 50;

    private final AccountQuery accountQuery;
    private final NlTransactionQueryTranslator translator;
    private final NlTransactionAnswerComposer composer;

    public AskTransactionHistoryResult ask(String accountId, String requesterId, String question) {
        accountQuery.findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));

        TransactionFilter filter = translator.translate(question);

        TransactionsWithCount result = accountQuery.findTransactions(
                new TransactionFindQuery(accountId, 0, MAX_TRANSACTIONS_FOR_ANSWER, filter.type(), filter.fromDate(), filter.toDate()));

        // ... map result.transactions() to TransactionSummary, omitted for brevity ...
        String answer = composer.compose(question, summaries);
        return new AskTransactionHistoryResult(answer, result.count());
    }
}
```

**The guardrail that makes this safe to let an LLM touch at all:** the translated filter may only narrow *what* is returned (a type/date range) — it must never influence *who* it belongs to. Account ownership is verified up front via `accountQuery.findAccounts(accountId, requesterId)`, using `requesterId` — the authenticated caller, set by the Controller from Spring Security's `Authentication` — never a value derived from the LLM's output; `TransactionFilter` doesn't even have an `ownerId`/`accountId` field. Worst case on a bad translation is an inaccurate answer about the requester's own data — never someone else's data or unauthorized access.

This is the deliberate opposite of a design mistake this repo made and later reversed: an earlier LLM feature let a model's read of user-submitted free text influence a security-relevant approve/reject judgment (a refund's fraud-risk signal, computed from the refund's own unverified `reason` text) — trivially gameable, since the same user supplying the text controlled the input the judgment was based on. The fix generalizes: **an LLM may narrow or shape what authorized data is shown, but must never be the thing that decides who is authorized, or approves/rejects a security- or money-relevant action, when its input is free text the affected party can influence.**

Both Technical Service implementations fail non-blockingly (the translator falls back to `new TransactionFilter(null, null, null)` — no narrowing; the composer falls back to a plain templated summary) — a translation/generation outage must never prevent an answer, even a plain one, same principle as any other Technical Service call in this repo. Both talk to a self-hosted Ollama instance (`docker-compose.yml`'s `ollama`/`ollama-init` services, the `qwen2.5:1.5b` model) over plain HTTP via `java.net.http.HttpClient` (Ollama has no official Java SDK) — the base URL/model name come from `config/LlmProperties.java`, a plain `@ConfigurationProperties` value (no secret involved, see [secret-manager.md](secret-manager.md)). `HttpClient` itself is exposed as a shared bean (`config/LlmHttpClientConfig.java`) rather than constructed internally by each Impl, specifically so a unit test can inject a mock `HttpClient` instead of making a real network call.

### Related code

- `implementations/nestjs/examples/src/account/application/query/ask-transaction-history-query-handler.ts` — the nestjs reference this was ported from
- `account/application/service/NlTransactionQueryTranslator.java`, `TransactionFilter.java`, `NlTransactionAnswerComposer.java`
- `account/infrastructure/NlTransactionQueryTranslatorImpl.java`, `NlTransactionAnswerComposerImpl.java`
- `account/application/query/AskTransactionHistoryService.java`, `AskTransactionHistoryResult.java`
- `account/domain/TransactionFindQuery.java` — the extended `findTransactions` query shape (optional `type`/`fromDate`/`toDate`, no `ownerId`)
- `config/LlmProperties.java`, `config/LlmHttpClientConfig.java`
- `account/infrastructure/NlTransactionQueryTranslatorImplTest.java`, `NlTransactionAnswerComposerImplTest.java` — unit tests mocking the shared `HttpClient` bean (valid response parsed, invalid/malformed values dropped, network failure falls back)
- `account/application/query/AskTransactionHistoryServiceTest.java` — mocks `AccountQuery`/`NlTransactionQueryTranslator`/`NlTransactionAnswerComposer`, pinning that the retrieval is always scoped by the authenticated requester regardless of what the mocked translator returns
- `account/interfaces/rest/AccountControllerE2ETest.java` — the `POST /accounts/{accountId}/transactions/ask` cases (no real Ollama in this test environment, so both LLM calls fall back to their non-blocking defaults — the tests assert only on response shape/count, not exact wording)

---

## Harness verification

`harness/src/rules/NoCrossAggregateReference.java` (rule: `no-cross-aggregate-reference`) checks that `payment/domain/Payment.java` never references the `Refund` type directly as a field/parameter, and that `payment/domain/Refund.java` never references `Payment` directly — the two Aggregates must only reference each other via an ID string like `paymentId` (see the `RefundEligibilityService` explanation above), and coordination logic must only ever go in a Domain Service. This rule is currently scoped to the one real case in this repository where a single BC has more than one Aggregate (the Payment BC).

---

### Related documents

- [domain-service.md (root)](../../../../docs/architecture/domain-service.md) — the framework-agnostic principles
- [tactical-ddd.md](tactical-ddd.md) — Aggregate design
- [cross-domain.md](cross-domain.md) — the Adapter pattern (its difference from Technical Service/Domain Service)
- [error-handling.md](error-handling.md) — the 1:1 `ErrorCode` mapping principle
