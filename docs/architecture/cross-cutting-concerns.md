# Cross-Cutting Concerns

Defines where and how to handle concerns that recur across multiple layers — authentication, logging, input validation, Correlation ID injection, and the like.

---

## Request pipeline

```
Request → [1. Pre-processing Middleware] → [2. Auth Guard] → [3. Input-validation Pipe] → [4. Handler] → [5. Response-transform Interceptor] → Response
```

Each stage has a clear responsibility and roles are never mixed.

| Stage | Role | Example |
|------|------|------|
| 1. Pre-processing | Set up context for every request | Injecting a Correlation ID, parsing the body |
| 2. Auth | Decide whether to allow/reject the request | JWT token verification, RBAC |
| 3. Input validation | Validate DTOs, convert types | Missing required field, type mismatch |
| 4. Handler | Run the business use case | Calling a Command/Query Service |
| 5. Response transform | Post-process the response, cross-cutting logging | HTTP request logging, measuring response time |

---

## Where each concern is handled

### Correlation ID injection (pre-processing stage)

Inject a Correlation ID into every request. If the client sends an `x-correlation-id` header, use it as-is; otherwise generate one on the server.

```typescript
// Pre-processing (conceptual)
function correlationIdMiddleware(req, res, next) {
  const correlationId = req.headers['x-correlation-id'] ?? generateId()
  res.setHeader('x-correlation-id', correlationId)
  correlationIdStorage.run(correlationId, () => next())
}
```

→ Propagate it via AsyncLocalStorage so every later stage can access it via `correlationIdStorage.getStore()`.

### Authentication (Guard stage)

Token verification and extracting user info are handled before entering the Handler. The Handler (a Controller method) just pulls the already-authenticated user info off the request object.

```typescript
// Guard (conceptual)
function authGuard(req): boolean {
  const token = req.headers['authorization']?.replace('Bearer ', '')
  if (!token) return false
  req.user = jwt.verify(token, secret)
  return true
}
```

→ Apply the Guard at the Controller class level. Applying it per-method risks missing one.

### Input validation (Pipe stage)

DTO validation runs before entering the Handler. The Handler only ever receives input that's already been validated.

```
Missing required field, type mismatch, exceeds length → 400 Bad Request (blocked at the validation stage)
A business-rule violation (an already-cancelled order, etc.) → handled inside the Handler
```

**Don't conflate input validation with business rules.** Formal validation (type, format, required fields) happens early in the pipeline; business-rule validation (checking domain state) happens in the Domain layer.

### HTTP request logging (response post-processing stage)

Log the HTTP request's method, URL, and response time right after the response. Don't log this inside the Handler.

```typescript
// Response transform / logging (conceptual)
async function loggingInterceptor(req, handler) {
  const start = Date.now()
  const result = await handler()
  logger.log({
    message: `${req.method} ${req.url}`,
    duration_ms: Date.now() - start,
    correlation_id: correlationIdStorage.getStore()
  })
  return result
}
```

---

## Cross-cutting concerns are forbidden in the Domain layer

Request-pipeline components — Middleware, Guard, Pipe, Interceptor, etc. — all **belong to the Interface layer.** Never use them in the Domain layer.

```typescript
// forbidden — using a logger/framework in the Domain layer
import { Logger } from '@nestjs/common'  // ← forbidden

export class Order {
  private readonly logger = new Logger(Order.name)  // ← forbidden
  public cancel(reason: string): void {
    this.logger.log('Order cancelled')  // ← forbidden
    ...
  }
}
```

---

## Principles

- **Use the stage that fits the role**: auth is a Guard, logging is an Interceptor, validation is a Pipe. Don't mix them.
- **Keep pre-processing as early as possible**: things every request needs — Correlation ID injection, auth — are handled early in the pipeline.
- **Keep the Handler pure**: the Handler (a Controller method) only calls a Service and converts errors. Don't write auth/validation logic directly in it.
- **Apply Guard/Interceptor at the class level**: only apply them per-method as an exception.

---

### Related docs

- [authentication.md](authentication.md) — details on the authentication pattern
- [observability.md](observability.md) — the logging pattern
- [error-handling.md](error-handling.md) — where error conversion happens
