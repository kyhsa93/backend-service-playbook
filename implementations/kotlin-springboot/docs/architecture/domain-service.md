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
desired state directly via `create()`/`complete()`/`cancel()`, then checks only the `evaluate()` result.
No Repository/DB appears anywhere.

Full code: `examples/.../payment/domain/{Payment.kt, Refund.kt, RefundEligibilityService.kt}`,
`examples/.../payment/application/command/RequestRefundService.kt`.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Payment/Refund Aggregate design
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Service separation
- [cross-domain.md](cross-domain.md) — `CardAdapter`/`AccountAdapter` synchronous queries (separate from a Domain Service — the "can this payment be made" judgment is an Adapter combination, not a Domain Service)
- root [domain-service.md](../../../../docs/architecture/domain-service.md) — framework-agnostic principles, the Technical Service pattern
