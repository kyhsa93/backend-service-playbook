# Error Handling Pattern

### Controller — catch-and-rethrow

```typescript
return this.service.doSomething(param).catch((error) => {
  this.logger.error(error)
  throw generateErrorResponse(error.message, [
    [ErrorMessage['Order not found.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND],
    [ErrorMessage['The order is already cancelled.'], BadRequestException, ErrorCode.ORDER_ALREADY_CANCELLED]
  ])
})
```

> `generateErrorResponse` is a project-wide common utility responsible for converting an error message → an HTTP exception + assigning a unique error code.
> The mapping tuple is a 3-tuple of `[error message, HttpException class, error code]`.
>
> ```typescript
> // src/common/generate-error-response.ts
> import { HttpException, HttpStatus, InternalServerErrorException } from '@nestjs/common'
>
> type ExceptionCtor = new (response: string | object) => HttpException
>
> export function generateErrorResponse(
>   message: string,
>   mappings: [string, ExceptionCtor, string][]
> ): HttpException {
>   const matched = mappings.find(([msg]) => msg === message)
>   const [, ExceptionClass, code] = matched ?? [null, InternalServerErrorException, 'INTERNAL_ERROR']
>   const probe = new ExceptionClass(message)
>   const statusCode = probe.getStatus()
>   const error = HttpStatus[statusCode] ?? probe.name
>   return new ExceptionClass({ statusCode, code, message, error })
> }
> ```

### Domain / Service — throw a plain Error (no HttpException)

The Domain layer and the Application Service only ever throw a plain `Error`.
Error messages reference the `ErrorMessage` enum everywhere, including inside the Aggregate.

```typescript
// domain/order.ts — references the enum even inside the Aggregate
import { OrderErrorMessage } from '@/order/order-error-message'
if (this._status === 'cancelled') throw new Error(OrderErrorMessage['The order is already cancelled.'])

// application/command/order-command-service.ts — the Command Service
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'
if (!order) throw new Error(ErrorMessage['Order not found.'])
```

If the argument to `throw new Error(...)` in the Domain/Application layer is a raw string rather than a `<Domain>ErrorMessage` enum reference, `harness/evaluators/rules/error-handling.evaluator.ts` catches it as `checklist.step7.domain.no-generic-error` / `checklist.step7.application.no-generic-error` respectively.

### Error Messages — Defined as an Enum (No Free-Form Strings)

```typescript
export enum OrderErrorMessage {
  'Order not found.' = 'Order not found.',
  'The order is already cancelled.' = 'The order is already cancelled.',
  'A paid order cannot be cancelled.' = 'A paid order cannot be cancelled.',
  'Payment information could not be found.' = 'Payment information could not be found.',
  'An order must have at least one item.' = 'An order must have at least one item.',
  'The product price must be greater than 0.' = 'The product price must be greater than 0.',
  'The quantity must be greater than 0.' = 'The quantity must be greater than 0.',
}
```

> The enum above is the Order-domain example. In a real project, error messages used by things like a Domain Service are added to the same enum file too.

### Error Codes — Defined as an Enum (1:1 Mapped with the Message)

Every error situation has a unique error code (string), separate from its message. If the HTTP status code is the "category," the error code is the "exact cause."
The client should branch on `code`, not the message text, so the code must be stable and separated from the message string, which may be translated or edited.

```typescript
// order-error-code.ts — <domain>-error-code.ts
export enum OrderErrorCode {
  ORDER_NOT_FOUND = 'ORDER_NOT_FOUND',
  ORDER_ALREADY_CANCELLED = 'ORDER_ALREADY_CANCELLED',
  ORDER_PAID_NOT_CANCELLABLE = 'ORDER_PAID_NOT_CANCELLABLE',
  PAYMENT_NOT_FOUND = 'PAYMENT_NOT_FOUND',
  ORDER_ITEMS_REQUIRED = 'ORDER_ITEMS_REQUIRED',
  INVALID_PRICE = 'INVALID_PRICE',
  INVALID_QUANTITY = 'INVALID_QUANTITY'
}
```

Code-writing rules:
- File name: `<domain>-error-code.ts` (module root)
- Class name: `<Domain>ErrorCode`
- Key/value: `SCREAMING_SNAKE_CASE`, value identical to the key
- Globally unique across the project — if it collides with another domain's code, add a prefix (things like `USER_ORDER_NOT_FOUND` are prohibited; use only that domain's own `ErrorCode`)
- Every entry in `<Domain>ErrorMessage` must have a 1:1-mapped code

### Import Alias — When Importing the Error Message / Error Code Enums

```typescript
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'
import { OrderErrorCode as ErrorCode } from '@/order/order-error-code'
```

### Error Response Format — the Standard JSON Structure

Every error response follows the format below. The client implements its error handling based on this format.

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
| `code` | `string` | A `<Domain>ErrorCode` enum value. The basis for the client's branching |
| `message` | `string` | The error message defined in `<Domain>ErrorMessage` (for display to the user) |
| `error` | `string` | The HTTP status text |

If the error response object constructed by the global exception filter doesn't match these exact 4 fields (`statusCode`/`code`/`message`/`error`) — a missing or an extra field — `harness/evaluators/rules/error-handling.evaluator.ts` catches it as `error-handling.response-schema.field-mismatch`.

On a validation failure (class-validator) — a case the framework itself throws, where `code` is fixed to `VALIDATION_FAILED`:

```json
{
  "statusCode": 400,
  "code": "VALIDATION_FAILED",
  "message": ["orderId must be a string", "reason must be longer than or equal to 1 characters"],
  "error": "Bad Request"
}
```

> To attach a `code` to the validation-failure response, return it from `app.useGlobalPipes(new ValidationPipe({ exceptionFactory: ... }))` in the form `BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })`.

### The Global Exception Filter

This repo has a custom `HttpExceptionFilter` registered via `app.useGlobalFilters(...)` (see [bootstrap.md](bootstrap.md)). Since the `HttpException` that `generateErrorResponse` constructs already carries a response object shaped `{ statusCode, code, message, error }`, it's serialized as-is. This filter's key role is catching even unhandled exceptions that aren't an `HttpException` (e.g. an unexpected `Error` throw) and converting them into the standard error response format — otherwise, NestJS's default handler would expose a raw 500 response including the stack trace.

```typescript
// src/common/http-exception.filter.ts — actual code
import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus, Logger } from '@nestjs/common'
import { Response } from 'express'

@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(HttpExceptionFilter.name)

  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp()
    const response = ctx.getResponse<Response>()

    if (exception instanceof HttpException) {
      const status = exception.getStatus()
      const exceptionResponse = exception.getResponse()

      this.logger.error({ status, message: exception.message })

      response.status(status).json(
        typeof exceptionResponse === 'string'
          ? { statusCode: status, code: 'HTTP_EXCEPTION', message: exceptionResponse, error: exception.name }
          : exceptionResponse
      )
      return
    }

    const status = HttpStatus.INTERNAL_SERVER_ERROR
    const message = exception instanceof Error ? exception.message : 'Internal server error'

    this.logger.error(exception instanceof Error ? exception.stack : exception)

    response.status(status).json({
      statusCode: status,
      code: 'INTERNAL_ERROR',
      message,
      error: 'Internal Server Error'
    })
  }
}
```
