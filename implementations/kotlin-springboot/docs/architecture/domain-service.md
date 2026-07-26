# Domain Service — Kotlin Spring Boot

> For the framework-agnostic principles and "when a Domain Service is needed," see [root domain-service.md](../../../../docs/architecture/domain-service.md).

## A real working example — `RefundEligibilityService` (cross-Aggregate coordination)

Since Account/Card are each a single-Aggregate BC, they couldn't demonstrate the very reason a Domain
Service exists — "logic that must read multiple Aggregates to make a judgment." The Payment BC, having
two Aggregates (`Payment`/`Refund`), closes this gap with real code.

**Domain rule**: "A refund's original payment must be in the COMPLETED state, and the refund amount cannot exceed the payment amount."

- The `Payment` Aggregate doesn't know about refund attempts (`Refund`) against itself — a refund only exists as a separate Aggregate.
- The `Refund` Aggregate doesn't know the original payment's amount/status — it only references it via `paymentId`.

Putting this judgment as a method on either Aggregate would require passing the entire other Aggregate
as a parameter, breaking the Aggregate boundary. So this judgment lives in a separate Domain Service
that the Application layer, having loaded both Aggregates, delegates to:

```kotlin
// payment/domain/RefundEligibilityService.kt — actual code
class RefundEligibilityService {
    fun evaluate(payment: Payment, refund: Refund): RefundDecision {
        if (payment.status != PaymentStatus.COMPLETED) {
            return RefundDecision(approved = false, reason = "A refund can only be requested for a completed payment.")
        }
        if (refund.amount > payment.amount) {
            return RefundDecision(approved = false, reason = "The refund amount cannot exceed the payment amount.")
        }
        return RefundDecision(approved = true)
    }
}

data class RefundDecision(
    val approved: Boolean,
    val reason: String? = null,
)
```

This is the only judgment `RefundEligibilityService` makes — no fraud-risk signal of any kind (LLM
classification or ML score) factors in. It remains a legitimate Domain Service purely because
coordinating the two Aggregates (Payment/Refund) for this comparison can't be done by either
Aggregate alone.

`RefundEligibilityService` is a plain class with no Spring annotation at all (`@Service`/`@Component`,
etc) — it isn't registered in the DI container. Since it's stateless, pure judgment logic, an
Application Service instantiates it directly (`RefundEligibilityService()`) and holds it as a field when
needed:

```kotlin
// payment/application/command/RequestRefundService.kt — actual code
@Service
class RequestRefundService(
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
) {
    private val refundEligibilityService = RefundEligibilityService()

    fun requestRefund(command: RequestRefundCommand): RequestRefundResult {
        val (payments, _) = paymentRepository.findPayments(
            PaymentFindQuery(page = 0, take = 1, paymentId = command.paymentId, ownerId = command.requesterId),
        )
        val payment = payments.firstOrNull() ?: throw PaymentNotFoundException(command.paymentId)

        val refund = Refund.create(paymentId = payment.paymentId, amount = command.amount, reason = command.reason)

        val decision = refundEligibilityService.evaluate(payment, refund)
        if (decision.approved) {
            refund.approve(payment.accountId, payment.ownerId)
        } else {
            refund.reject(decision.reason ?: "The refund request was rejected.")
        }

        refundRepository.saveRefund(refund)
        // Returns immediately here — draining the Outbox (OutboxPoller/OutboxConsumer) is handled
        // independently by a separate component (domain-events.md).
        return RequestRefundResult(/* ... */)
    }
}
```

Loading the two Repositories (`PaymentRepository`, `RefundRepository`) together is precisely the
Application layer's responsibility — the Domain Service only makes a judgment given two already-loaded
Aggregates; it never performs the query itself (see "the anti-pattern for using a Domain Service" in
the root document).

**A rejection is a valid domain outcome, not an exception.** When `decision.approved == false`,
`refund.reject(...)` saves it as `RefundStatus.REJECTED` and returns it as-is — it never throws.
`PaymentController.requestRefund` still responds to this result with `201 Created` + `status:
"REJECTED"` + `decisionNote` (it is never expressed as a 4xx). This reflects the domain's point of view
directly onto the HTTP surface: the refund "request" itself was evaluated successfully, and the
conclusion just happened to be a rejection.

The **unit test** instantiates `RefundEligibilityService()` directly, without going through the
Application layer, and verifies only the judgment logic
(`payment/domain/RefundEligibilityServiceTest.kt`) — it puts the Payment/Refund Aggregates into the
desired state directly via `create()`/`complete()`/`cancel()`, then checks only the `evaluate()`
result. No Repository/DB, no mocking, and no external technical-service dependency of any kind
appears anywhere in this test.

Full code: `examples/.../payment/domain/{Payment.kt, Refund.kt, RefundEligibilityService.kt}`,
`examples/.../payment/application/command/RequestRefundService.kt`.

## A real, working example — a structured-data RAG pipeline (two LLM Technical Services + a Query Service orchestrating them)

`AskTransactionHistoryService` (Account BC) answers a free-text question about an account's transaction history — e.g. "How much did I deposit this month?" — using two LLM-backed Technical Services either side of an ordinary Repository read, following the Retrieve → Augment → Generate shape of RAG (here, "Retrieve" is a structured DB query, not a vector-embedding search — commonly called "structured-data RAG" or "RAG over a database" to distinguish it from the canonical vector-search form):

1. **Retrieve-preparation** — `NlTransactionQueryTranslator` (a Technical Service, LLM call) turns the question into a structured filter (`type`/`fromDate`/`toDate`).
2. **Retrieve** — `AccountQuery.findTransactions` runs that filter, scoped to the account (an ordinary Query, no LLM involved).
3. **Generate** — `NlTransactionAnswerComposer` (a second Technical Service, LLM call) answers the question, grounded only in the retrieved records.

```kotlin
// application/service/TransactionFilter.kt — the interface's return shape
data class TransactionFilter(
    val type: TransactionType? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
)

// application/service/NlTransactionQueryTranslator.kt — the interface
interface NlTransactionQueryTranslator {
    fun translate(question: String): TransactionFilter
}

// application/service/NlTransactionAnswerComposer.kt — the interface
interface NlTransactionAnswerComposer {
    fun compose(question: String, transactions: List<GetTransactionsResult.TransactionSummary>): String
}
```

All orchestration lives in the Query Service (the Application layer) — never in the Controller, which only wraps the HTTP request into DTOs and dispatches here:

```kotlin
// application/query/AskTransactionHistoryService.kt — actual code (excerpt)
@Service
@Transactional(readOnly = true)
class AskTransactionHistoryService(
    private val accountQuery: AccountQuery,
    private val translator: NlTransactionQueryTranslator,
    private val composer: NlTransactionAnswerComposer,
) {
    private val maxTransactionsForAnswer = 50

    fun ask(accountId: String, requesterId: String, question: String): AskTransactionHistoryResult {
        val (accounts, _) = accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId))
        accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)

        val filter = translator.translate(question)

        val (transactions, count) = accountQuery.findTransactions(
            TransactionFindQuery(accountId, 0, maxTransactionsForAnswer, filter.type, filter.fromDate, filter.toDate),
        )

        // ... map transactions to TransactionSummary, omitted for brevity ...
        val answer = composer.compose(question, summaries)
        return AskTransactionHistoryResult(answer, count)
    }
}
```

**The guardrail that makes this safe to let an LLM touch at all:** the translated filter may only narrow *what* is returned (a type/date range) — it must never influence *who* it belongs to. Account ownership is verified up front via `accountQuery.findAccounts(accountId, requesterId)`, using `requesterId` — the authenticated caller, set by the Controller from Spring Security's `Authentication` — never a value derived from the LLM's output; `TransactionFilter` doesn't even have an `ownerId`/`accountId` field. Worst case on a bad translation is an inaccurate answer about the requester's own data — never someone else's data or unauthorized access.

This is the deliberate opposite of a design mistake this repo made and later reversed: an earlier LLM feature let a model's read of user-submitted free text influence a security-relevant approve/reject judgment (a refund's fraud-risk signal, computed from the refund's own unverified `reason` text) — trivially gameable, since the same user supplying the text controlled the input the judgment was based on. The fix generalizes: **an LLM may narrow or shape what authorized data is shown, but must never be the thing that decides who is authorized, or approves/rejects a security- or money-relevant action, when its input is free text the affected party can influence.**

Both Technical Service implementations fail non-blockingly (the translator falls back to `TransactionFilter()` — no narrowing; the composer falls back to a plain templated summary) — a translation/generation outage must never prevent an answer, even a plain one, same principle as any other Technical Service call in this repo. Both talk to a self-hosted Ollama instance (`docker-compose.yml`'s `ollama`/`ollama-init` services, the `qwen2.5:1.5b` model) over plain HTTP via `java.net.http.HttpClient` (Ollama has no official Kotlin/JVM SDK) — the base URL/model name come from `config/LlmProperties.kt`, a plain `@ConfigurationProperties` `data class` (no secret involved, see [secret-manager.md](secret-manager.md)). `HttpClient` itself is exposed as a shared bean (`config/LlmHttpClientConfig.kt`) rather than constructed internally by each Impl, specifically so a unit test can inject a mock `HttpClient` instead of making a real network call.

### Related code

- `implementations/nestjs/examples/src/account/application/query/ask-transaction-history-query-handler.ts` — the nestjs reference this was ported from
- `account/application/service/NlTransactionQueryTranslator.kt`, `TransactionFilter.kt`, `NlTransactionAnswerComposer.kt`
- `account/infrastructure/NlTransactionQueryTranslatorImpl.kt`, `NlTransactionAnswerComposerImpl.kt`
- `account/application/query/AskTransactionHistoryService.kt`, `AskTransactionHistoryResult.kt`
- `account/domain/AccountRepository.kt` — the extended `TransactionFindQuery` query shape (optional `type`/`fromDate`/`toDate`, no `ownerId`)
- `config/LlmProperties.kt`, `config/LlmHttpClientConfig.kt`
- `account/infrastructure/NlTransactionQueryTranslatorImplTest.kt`, `NlTransactionAnswerComposerImplTest.kt` — unit tests mocking the shared `HttpClient` bean (valid response parsed, invalid/malformed values dropped, network failure falls back)
- `account/application/query/AskTransactionHistoryServiceTest.kt` — mocks `AccountQuery`/`NlTransactionQueryTranslator`/`NlTransactionAnswerComposer`, pinning that the retrieval is always scoped by the authenticated requester regardless of what the mocked translator returns
- `account/interfaces/rest/AccountControllerE2ETest.kt` — the `POST /accounts/{accountId}/transactions/ask` cases (no real Ollama in this test environment, so both LLM calls fall back to their non-blocking defaults — the tests assert only on response shape/count, not exact wording)

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Payment/Refund Aggregate design
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Service separation
- [cross-domain.md](cross-domain.md) — `CardAdapter`/`AccountAdapter` synchronous queries (separate from a Domain Service — the "can this payment be made" judgment is an Adapter combination, not a Domain Service)
- root [domain-service.md](../../../../docs/architecture/domain-service.md) — framework-agnostic principles, the Technical Service pattern
