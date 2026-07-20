# Domain Service / Technical Service 패턴 (Spring Boot)

> Domain Service가 필요한 경우, Domain Service vs Application Service vs Technical Service 구분, Domain Service를 잘못 쓰는 패턴은 루트 [domain-service.md](../../../../docs/architecture/domain-service.md)를 참고한다. 이 문서는 이 저장소가 실제로 갖고 있는 Java 구현을 다룬다.

## 이 저장소의 현재 상태

`examples/`에는 두 종류의 예시가 있다.

- **Technical Service**: `account/application/service/NotificationService.java`(인터페이스) + `account/infrastructure/notification/NotificationServiceImpl.java`(구현체, SES). 기술 인프라(이메일 발송)를 추상화하지만, 여러 Aggregate를 조율하는 도메인 판단 로직은 아니다.
- **Domain Service (진짜 cross-Aggregate 조율)**: `payment/domain/RefundEligibilityService.java`. `Payment`/`Refund` 두 Aggregate를 함께 로드해야만 내릴 수 있는 판단("원 결제가 COMPLETED 상태여야 하고, 환불 금액이 결제 금액을 넘을 수 없다")을 조율한다 — 루트 문서가 정의하는 "여러 Aggregate를 읽어서 판단해야 하는 로직"의 실제 동작하는 예시다.

Account/Card 두 BC는 각자 단일 Aggregate만 가지므로 이 패턴 자체를 보여줄 수 없었다 — Payment BC(Payment/Refund 두 Aggregate)가 추가되며 이 gap이 닫혔다.

---

## RefundEligibilityService — cross-Aggregate 조율 예시

`Payment` Aggregate는 자신에 대한 환불 시도를 모른다(환불은 `Refund`라는 별도 Aggregate로만 존재한다). `Refund` Aggregate는 원 결제의 금액·상태를 모른다(`paymentId`로 참조만 한다). 이 판단을 내리려면 두 Aggregate를 모두 로드해 같은 자리에서 비교해야 하므로, 어느 한쪽 Aggregate의 메서드로는 넣을 수 없다(넣는다면 다른 쪽 Aggregate 전체를 파라미터로 받아야 해 경계가 무너진다).

### Step 1 — `domain/`에 프레임워크 애노테이션 없는 순수 클래스로 정의

```java
// payment/domain/RefundEligibilityService.java — 실제 코드
public class RefundEligibilityService {

    public RefundDecision evaluate(Payment payment, Refund refund) {
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_REQUIRES_COMPLETED_PAYMENT,
                    "완료된 결제에 대해서만 환불을 요청할 수 있습니다.");
        }
        if (refund.getAmount() > payment.getAmount()) {
            return RefundDecision.rejected(
                    PaymentException.ErrorCode.REFUND_AMOUNT_EXCEEDS_PAYMENT,
                    "환불 금액은 결제 금액을 초과할 수 없습니다.");
        }
        return RefundDecision.approve();
    }
}
```

```java
// payment/domain/RefundDecision.java — 판단 결과. 거부돼도 예외가 아니라 값으로 반환한다.
public record RefundDecision(boolean approved, PaymentException.ErrorCode code, String reason) {
    public static RefundDecision approve() { return new RefundDecision(true, null, null); }
    public static RefundDecision rejected(PaymentException.ErrorCode code, String reason) {
        return new RefundDecision(false, code, reason);
    }
}
```

`@Service`/`@Component` 등 어떤 스테레오타입 애노테이션도 없다 — Spring 빈으로 등록하지 않는다.

### Step 2 — Application Service가 두 Repository를 로드해 위임

```java
// payment/application/command/RequestRefundService.java — 실제 코드(일부)
@Service
@RequiredArgsConstructor
public class RequestRefundService {

    // 상태 없는 순수 판단 로직이라 Spring DI 대신 직접 인스턴스화한다 — 매 요청 재사용에 문제가 없다.
    private final RefundEligibilityService refundEligibilityService = new RefundEligibilityService();

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public GetRefundResult request(RequestRefundCommand command) {
        Payment payment = /* paymentRepository.findPayments(...)로 소유권 검증 후 로드 */;
        Refund refund = Refund.create(payment.getPaymentId(), command.amount(), command.reason());

        RefundDecision decision = refundEligibilityService.evaluate(payment, refund);
        if (decision.approved()) {
            refund.approve(payment.getAccountId(), payment.getOwnerId());
        } else {
            refund.reject(decision.reason());
        }

        refundRepository.saveRefund(refund);   // @Transactional — Refund 저장 + Outbox 적재, 한 트랜잭션(persistence.md 참고)
        return GetRefundResult.from(refund);   // 저장 후 곧바로 반환 — 드레인은 OutboxPoller/OutboxConsumer가 비동기로 담당(domain-events.md 참고)
    }
}
```

- `RefundEligibilityService`는 필드로 두되 `@RequiredArgsConstructor`(Lombok 생성자 주입) 대상이 아니라 `new`로 직접 초기화한다 — 다른 필드(`PaymentRepository` 등, Spring이 주입하는 협력자)와 대비된다.
- 환불 거부(`decision.approved() == false`)는 예외가 아니라 유효한 도메인 결론이다 — `RequestRefundService.request()`는 이 경우에도 throw하지 않고 `REJECTED` 상태로 저장한 `Refund`를 그대로 반환한다. `PaymentController`는 이를 에러가 아닌 `201 Created` + `status: "REJECTED"`로 응답한다(Payment BC가 결정한 설계, `interfaces/rest/PaymentController.java` 참고).

### RefundDecision과 PaymentException.ErrorCode — 판단 결과에도 에러 코드 부여

이 저장소의 [error-handling.md](error-handling.md)는 "가드 조건 1개 = `ErrorCode` 1개"를 요구한다. 환불 거부는 예외로 throw되지 않지만, 어떤 규칙이 거부를 만들었는지 추적할 수 있도록 `RefundDecision`이 `PaymentException.ErrorCode`를 데이터로 들고 있다(`REFUND_REQUIRES_COMPLETED_PAYMENT`/`REFUND_AMOUNT_EXCEEDS_PAYMENT`) — nestjs 레퍼런스 구현체가 메시지 문자열만 반환하는 것보다 한 단계 더 타입화한 지점이다.

### 관련 코드

- `payment/domain/Payment.java`, `Refund.java`, `RefundEligibilityService.java`, `RefundDecision.java`, `PaymentException.java`
- `payment/application/command/RequestRefundService.java` — Domain Service 호출 지점
- `payment/domain/RefundEligibilityServiceTest.java` — Spring 컨텍스트 없이 `new`로 직접 인스턴스화해 판단 로직만 검증하는 단위 테스트
- `payment/interfaces/rest/PaymentControllerE2ETest.java` — 환불 승인/거부 두 경로 모두 실제 HTTP API로 검증

---

## Technical Service — NotificationService

Technical Service 배치 원칙(도메인 내부가 기본값, YAGNI)과 `NotificationService`/`NotificationServiceImpl` 코드는 [file-storage.md](file-storage.md)/[secret-manager.md](secret-manager.md)/[directory-structure.md](directory-structure.md)가 이미 반복 인용하므로 여기서는 중복하지 않는다.

---

## harness 검증

`harness/src/rules/NoCrossAggregateReference.java`(rule: `no-cross-aggregate-reference`)가 `payment/domain/Payment.java`는 `Refund` 타입을, `payment/domain/Refund.java`는 `Payment` 타입을 필드/파라미터로 직접 참조하지 않는지 확인한다 — 두 Aggregate는 `paymentId` 같은 ID 문자열로만 서로를 참조해야 하고(위 `RefundEligibilityService` 설명 참고), 조율 로직은 Domain Service 자리로만 가야 한다. 현재 이 저장소에서 한 BC 안에 Aggregate가 둘 이상인 유일한 실제 사례(Payment BC)에 한정한 규칙이다.

---

### 관련 문서

- [domain-service.md (root)](../../../../docs/architecture/domain-service.md) — 프레임워크 무관 원칙
- [tactical-ddd.md](tactical-ddd.md) — Aggregate 설계
- [cross-domain.md](cross-domain.md) — Adapter 패턴(Technical Service·Domain Service와의 차이)
- [error-handling.md](error-handling.md) — `ErrorCode` 1:1 매핑 원칙
