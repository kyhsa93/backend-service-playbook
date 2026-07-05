# 에러 처리 패턴

### 계층별 에러 처리 원칙

| 레이어 | 에러 처리 방식 |
|---|---|
| Domain / Application | plain `Error` throw. 프레임워크 HTTP 예외 사용 금지 |
| Interface (Controller) | 에러를 catch → HTTP 상태 코드로 변환 후 re-throw |

이 분리를 통해 Domain/Application 레이어는 HTTP에 의존하지 않고, 에러 변환 책임은 Interface 레이어에만 집중된다.

---

### Domain / Application — plain Error throw

Domain 레이어와 Application Service에서는 plain `Error`만 throw한다. 에러 메시지는 타입화된 enum을 참조한다.

```typescript
// domain/order.ts — Aggregate 내부
if (this._status === 'cancelled') throw new Error(OrderErrorMessage['이미 취소된 주문입니다.'])

// application/command/order-command-service.ts
if (!order) throw new Error(OrderErrorMessage['주문을 찾을 수 없습니다.'])
```

---

### 에러 메시지 — enum으로 타입화 (free-form 문자열 금지)

```typescript
// order-error-message.ts
export enum OrderErrorMessage {
  '주문을 찾을 수 없습니다.' = '주문을 찾을 수 없습니다.',
  '이미 취소된 주문입니다.' = '이미 취소된 주문입니다.',
  '결제 완료된 주문은 취소할 수 없습니다.' = '결제 완료된 주문은 취소할 수 없습니다.',
  '주문 항목은 최소 1개 이상이어야 합니다.' = '주문 항목은 최소 1개 이상이어야 합니다.',
}
```

free-form 문자열을 사용하면 오타나 메시지 수정 시 Interface 레이어의 매핑이 조용히 깨진다. enum으로 정의하면 컴파일 타임에 감지할 수 있다.

---

### 에러 코드 — enum으로 정의 (메시지와 1:1 매핑)

모든 에러 상황은 고유한 에러 코드(string)를 가진다. HTTP 상태 코드가 "범주"라면 에러 코드는 "정확한 원인"이다.
클라이언트는 메시지 텍스트가 아닌 `code`로 분기 처리해야 하므로, 코드는 안정적이어야 하며 번역/수정될 수 있는 메시지 문자열과 분리한다.

```typescript
// order-error-code.ts
export enum OrderErrorCode {
  ORDER_NOT_FOUND = 'ORDER_NOT_FOUND',
  ORDER_ALREADY_CANCELLED = 'ORDER_ALREADY_CANCELLED',
  ORDER_PAID_NOT_CANCELLABLE = 'ORDER_PAID_NOT_CANCELLABLE',
  ORDER_ITEMS_REQUIRED = 'ORDER_ITEMS_REQUIRED',
}
```

코드 작성 규칙:
- 키/값: `SCREAMING_SNAKE_CASE`, 값은 키와 동일 문자열
- 프로젝트 전역 유일 — 다른 도메인 코드와 충돌 시 도메인 prefix 추가
- `<Domain>ErrorMessage`의 모든 항목에 대해 1:1 매핑되는 코드가 존재해야 한다

---

### Interface 레이어 — 에러 변환

Controller에서 에러를 catch하여 HTTP 예외로 변환한다. 변환 시 에러 메시지 → HTTP 상태 코드 매핑과 고유 에러 코드를 부여한다.

```typescript
// interface/order-controller.ts (개념)
public async getOrder(param: GetOrderRequestParam): Promise<GetOrderResponseBody> {
  return this.orderQueryService.getOrder(param).catch((error) => {
    // error.message를 HTTP 예외로 변환
    throw convertToHttpError(error.message, [
      [OrderErrorMessage['주문을 찾을 수 없습니다.'], 404, OrderErrorCode.ORDER_NOT_FOUND],
      [OrderErrorMessage['이미 취소된 주문입니다.'], 400, OrderErrorCode.ORDER_ALREADY_CANCELLED]
    ])
  })
}
```

매핑에 없는 에러는 500 Internal Server Error로 처리한다.

---

### 에러 응답 형식 — 표준 JSON 구조

모든 에러 응답은 아래 형식을 따른다.

```json
{
  "statusCode": 404,
  "code": "ORDER_NOT_FOUND",
  "message": "주문을 찾을 수 없습니다.",
  "error": "Not Found"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `statusCode` | `number` | HTTP 상태 코드 |
| `code` | `string` | `<Domain>ErrorCode` enum 값. 클라이언트 분기 처리의 기준 |
| `message` | `string` | `<Domain>ErrorMessage` enum에 정의된 에러 메시지 (사용자 표시용) |
| `error` | `string` | HTTP 상태 텍스트 |

Validation 실패 시 — `code`는 `VALIDATION_FAILED` 고정:

```json
{
  "statusCode": 400,
  "code": "VALIDATION_FAILED",
  "message": ["orderId must be a string"],
  "error": "Bad Request"
}
```

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 내부 에러 throw 패턴
- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
