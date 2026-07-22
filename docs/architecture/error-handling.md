# Error-Handling Pattern

### Error-handling principle per layer

| Layer | How errors are handled |
|---|---|
| Domain / Application | throw a plain `Error`. Never use a framework's HTTP exception |
| Interface (Controller) | catch the error → convert it to an HTTP status code, then re-throw |

This separation keeps the Domain/Application layers free of any HTTP dependency, concentrating error-conversion responsibility solely in the Interface layer.

---

### Domain / Application — throw a plain Error

The Domain layer and Application Services only ever throw a plain `Error`. The error message references a typed enum.

```typescript
// domain/order.ts — inside the Aggregate
if (this._status === 'cancelled') throw new Error(OrderErrorMessage['This order has already been cancelled.'])

// application/command/order-command-service.ts
if (!order) throw new Error(OrderErrorMessage['Order not found.'])
```

---

### Error messages — typed as an enum (no free-form strings)

```typescript
// order-error-message.ts
export enum OrderErrorMessage {
  'Order not found.' = 'Order not found.',
  'This order has already been cancelled.' = 'This order has already been cancelled.',
  'A paid order cannot be cancelled.' = 'A paid order cannot be cancelled.',
  'An order must have at least one item.' = 'An order must have at least one item.',
}
```

**Why the key and value are the same string:**

The Interface layer compares `error.message` against the enum values to convert it into an HTTP exception.

```typescript
// The Interface layer's mapping
[OrderErrorMessage['Order not found.'], 404, OrderErrorCode.ORDER_NOT_FOUND]
//  ↑ the enum key (checked at compile time)      ↑ this value is compared against error.message at runtime
```

If the key ≠ the value, two problems arise:
1. If the Aggregate writes the value directly, e.g. `throw new Error('Order not found.')`, a typo produces no compile error
2. In the Interface layer, comparing `OrderErrorMessage['Order not found.']` against `error.message` fails

Defining key = value means the message can only be written **through the enum key**, as in `throw new Error(OrderErrorMessage['Order not found.'])`, so a typo shows up at compile time.

---

### Error codes — defined as an enum (1:1 mapped to messages)

Every error situation has its own unique error code (a string). If the HTTP status code is the "category," the error code is the "precise cause."
Since the client should branch on `code`, not the message text, the code must be stable and kept separate from the message string, which can be translated/edited.

```typescript
// order-error-code.ts
export enum OrderErrorCode {
  ORDER_NOT_FOUND = 'ORDER_NOT_FOUND',
  ORDER_ALREADY_CANCELLED = 'ORDER_ALREADY_CANCELLED',
  ORDER_PAID_NOT_CANCELLABLE = 'ORDER_PAID_NOT_CANCELLABLE',
  ORDER_ITEMS_REQUIRED = 'ORDER_ITEMS_REQUIRED',
}
```

Rules for writing codes:
- Key/value: `SCREAMING_SNAKE_CASE`, with the value identical to the key
- Unique across the whole project — add a domain prefix if it collides with another domain's code
- Every entry in `<Domain>ErrorMessage` must have a 1:1-mapped code

---

### The Interface layer — converting errors

The Controller catches the error and converts it to an HTTP exception. Conversion assigns both the error-message → HTTP-status-code mapping and a unique error code.

```typescript
// interface/order-controller.ts (conceptual)
public async getOrder(param: GetOrderRequestParam): Promise<GetOrderResponseBody> {
  return this.orderQueryService.getOrder(param).catch((error) => {
    // convert error.message into an HTTP exception
    throw convertToHttpError(error.message, [
      [OrderErrorMessage['Order not found.'], 404, OrderErrorCode.ORDER_NOT_FOUND],
      [OrderErrorMessage['This order has already been cancelled.'], 400, OrderErrorCode.ORDER_ALREADY_CANCELLED]
    ])
  })
}
```

An error with no entry in the mapping is treated as a 500 Internal Server Error.

---

### Error-response format — the standard JSON structure

Every error response follows this format.

```json
{
  "statusCode": 404,
  "code": "ORDER_NOT_FOUND",
  "message": "Order not found.",
  "error": "Not Found"
}
```

| Field | Type | Description |
|------|------|------|
| `statusCode` | `number` | The HTTP status code |
| `code` | `string` | A `<Domain>ErrorCode` enum value. What the client branches on |
| `message` | `string` | The error message defined in the `<Domain>ErrorMessage` enum (for display to the user) |
| `error` | `string` | The HTTP status text |

On a validation failure — `code` is fixed to `VALIDATION_FAILED`:

```json
{
  "statusCode": 400,
  "code": "VALIDATION_FAILED",
  "message": ["orderId must be a string"],
  "error": "Bad Request"
}
```

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — the pattern for throwing errors inside an Aggregate
- [layer-architecture.md](layer-architecture.md) — the separation of responsibilities per layer
