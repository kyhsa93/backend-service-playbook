# Domain Service — Kotlin Spring Boot

> 프레임워크 무관 원칙과 "Domain Service가 필요한 경우"는 [root domain-service.md](../../../../docs/architecture/domain-service.md) 참조.

## 실제 동작하는 예시 — `RefundEligibilityService` (cross-Aggregate 조율)

Account/Card는 각자 단일 Aggregate BC라 "여러 Aggregate를 읽어서 판단해야 하는 로직"이라는 Domain
Service 존재 이유 자체를 보여줄 수 없었다. Payment BC는 `Payment`/`Refund` 두 Aggregate를 두면서
이 gap을 실제 코드로 닫는다.

**도메인 규칙**: "환불은 원 결제가 COMPLETED 상태여야 하고, 환불 금액이 결제 금액을 넘을 수 없다."

- `Payment` Aggregate는 자신에 대한 환불 시도(`Refund`)를 모른다 — 환불은 별도 Aggregate로만 존재한다.
- `Refund` Aggregate는 원 결제의 금액·상태를 모른다 — `paymentId`로 참조만 한다.

어느 한쪽 Aggregate의 메서드로 이 판단을 넣으려면 다른 쪽 Aggregate 전체를 파라미터로 받아야 해서
Aggregate 경계가 무너진다. 그래서 이 판단은 두 Aggregate를 모두 로드한 Application 레이어가
위임하는 별도의 Domain Service에 위치한다:

```kotlin
// payment/domain/RefundEligibilityService.kt — 실제 코드
class RefundEligibilityService {
    fun evaluate(payment: Payment, refund: Refund): RefundDecision {
        if (payment.status != PaymentStatus.COMPLETED) {
            return RefundDecision(approved = false, reason = "완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
        }
        if (refund.amount > payment.amount) {
            return RefundDecision(approved = false, reason = "환불 금액은 결제 금액을 초과할 수 없습니다.")
        }
        return RefundDecision(approved = true)
    }
}

data class RefundDecision(
    val approved: Boolean,
    val reason: String? = null,
)
```

`RefundEligibilityService`는 `@Service`/`@Component` 등 어떤 Spring 어노테이션도 없는 순수
클래스다 — DI 컨테이너에 등록하지 않는다. 상태가 없는 순수 판단 로직이라 Application Service가
필요할 때 생성자를 직접 호출해(`RefundEligibilityService()`) 필드로 보유한다:

```kotlin
// payment/application/command/RequestRefundService.kt — 실제 코드
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
            refund.reject(decision.reason ?: "환불 요청이 거부되었습니다.")
        }

        refundRepository.saveRefund(refund)
        // 여기서 곧바로 반환한다 — Outbox 드레인(OutboxPoller/OutboxConsumer)은 별도 컴포넌트가
        // 독립적으로 담당한다(domain-events.md).
        return RequestRefundResult(/* ... */)
    }
}
```

두 Repository(`PaymentRepository`, `RefundRepository`)를 함께 로드하는 것은 정확히 Application
레이어의 책임이다 — Domain Service는 이미 로드된 두 Aggregate를 받아 판단만 하고, 조회 자체는
하지 않는다("Domain Service를 잘못 쓰는 패턴" 참고, root 문서).

**거부는 예외가 아니라 유효한 도메인 아웃컴이다.** `decision.approved == false`인 경우
`refund.reject(...)`가 `RefundStatus.REJECTED`로 저장하고 그대로 반환한다 — throw하지 않는다.
`PaymentController.requestRefund`는 이 결과를 여전히 `201 Created` + `status: "REJECTED"` +
`decisionNote`로 응답한다(4xx로 표현하지 않는다). 환불 "요청" 자체는 성공적으로 평가되었고, 그
결론이 거부였을 뿐이라는 도메인 관점을 그대로 HTTP 표면에 반영한다.

**단위 테스트**는 Application 레이어를 거치지 않고 `RefundEligibilityService()`를 직접
인스턴스화해 판단 로직만 검증한다(`payment/domain/RefundEligibilityServiceTest.kt`) — Payment/
Refund Aggregate를 직접 `create()`/`complete()`/`cancel()`로 원하는 상태로 만든 뒤 `evaluate()`
결과만 확인한다. Repository/DB는 전혀 등장하지 않는다.

전체 코드: `examples/.../payment/domain/{Payment.kt, Refund.kt, RefundEligibilityService.kt}`,
`examples/.../payment/application/command/RequestRefundService.kt`.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Payment/Refund Aggregate 설계
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Service 분리
- [cross-domain.md](cross-domain.md) — `CardAdapter`/`AccountAdapter` 동기 조회(Domain Service와는 별개로, "결제 가능 여부" 판단은 Adapter 조합이지 Domain Service가 아니다)
- root [domain-service.md](../../../../docs/architecture/domain-service.md) — 프레임워크 무관 원칙, Technical Service 패턴
