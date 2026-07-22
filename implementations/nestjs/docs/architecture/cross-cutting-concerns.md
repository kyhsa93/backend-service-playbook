# Middleware / Guard / Interceptor / Pipe

Use the 4 components of the NestJS request pipeline distinctly, according to their role.

## Execution Order

```
Request → Middleware → Guard → Interceptor (before) → Pipe → Handler → Interceptor (after) → Response
```

Each component has a clear role — don't place logic that doesn't fit that role.

## Role Breakdown

| Component | Role | Information available | Example |
|----------|------|--------------|------|
| **Middleware** | Request/response preprocessing, setting up context | `req`, `res`, `next` | Injecting a Correlation ID, body parsing |
| **Guard** | Authorization (allow/deny the request) | `ExecutionContext` | Verifying an auth token, role-based access control |
| **Interceptor** | Transforming the request/response, cross-cutting concerns | `ExecutionContext`, `CallHandler` | Logging, measuring response time, transforming the response |
| **Pipe** | Transforming/validating input data | Parameter values | DTO conversion, `ValidationPipe` |

## Middleware

Runs before the request reaches the route handler. Uses the same `(req, res, next)` signature as Express middleware.

### When to use

- Preprocessing applied to every request (Correlation ID, request logging)
- Injecting context information into the request object

### Implementation pattern

```typescript
// src/common/correlation-id.middleware.ts
@Injectable()
export class CorrelationIdMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction) {
    const correlationId = (req.headers['x-correlation-id'] as string) ?? randomUUID()
    res.setHeader('x-correlation-id', correlationId)
    CorrelationIdStore.run(correlationId, () => next())
  }
}
```

### Registration

```typescript
// src/app-module.ts
@Module({ /* ... */ })
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(CorrelationIdMiddleware).forRoutes('*')
  }
}
```

### Layer placement

Placed in `src/common/`. Belongs to a common module, not a domain module.

## Guard

Decides whether a request is authorized. If `canActivate()` returns `true`, the request proceeds; if `false`, a `ForbiddenException` is raised.

### When to use

- Verifying an auth token (JWT Bearer)
- Role-based access control (RBAC)

### Implementation pattern

```typescript
// src/auth/auth.guard.ts
@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private readonly authService: AuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest()
    const token = request.headers.authorization?.replace('Bearer ', '')
    if (!token) return false
    // A Guard has no callback to wrap the rest of the pipeline, so it can't itself populate
    // UserContextStore — this field is an internal handoff to UserContextInterceptor, which
    // always runs alongside it via @Authenticated(). See authentication.md.
    request.__verifiedUser = await this.authService.verify(token)
    return true
  }
}
```

### Application

Apply `@Authenticated()` (which bundles `AuthGuard` + `UserContextInterceptor`) at the Controller **class level**. Avoid method-level application.

```typescript
@Authenticated()
@Controller('orders')
export class OrderController { /* ... */ }
```

### Layer placement

Placed in `src/auth/` (`AuthGuard`, `@Authenticated()`) and `src/common/` (`UserContextStore`, `UserContextInterceptor` — shared, not auth-specific). See [authentication.md](authentication.md) for the detailed pattern.

## Interceptor

Runs before and after the request, transforming the response or handling cross-cutting concerns (logging, timing, etc.).

### When to use

- Logging HTTP requests/responses (method, URL, elapsed time)
- Transforming response data
- Handling timeouts

### Implementation pattern

```typescript
// src/common/logging.interceptor.ts
@Injectable()
export class LoggingInterceptor implements NestInterceptor {
  private readonly logger = new Logger('HTTP')

  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const req = context.switchToHttp().getRequest()
    const { method, url } = req
    const now = Date.now()

    return next.handle().pipe(
      tap(() => this.logger.log({
        message: `${method} ${url}`,
        duration_ms: Date.now() - now
      }))
    )
  }
}
```

### Application

Apply it globally, or at the Controller class level.

```typescript
// globally — main.ts
app.useGlobalInterceptors(new LoggingInterceptor())

// class level
@UseInterceptors(LoggingInterceptor)
@Controller('orders')
export class OrderController { /* ... */ }
```

### Layer placement

Placed in `src/common/`.

## Pipe

Transforms or validates a parameter. Mainly used globally as `ValidationPipe`.

### When to use

- DTO validation (`ValidationPipe`)
- Parameter type conversion (`ParseIntPipe`, `ParseUUIDPipe`)

### Global configuration

```typescript
// src/main.ts
app.useGlobalPipes(new ValidationPipe({
  whitelist: true,
  forbidNonWhitelisted: true,
  transform: true
}))
```

### Applying to an individual parameter

```typescript
@Get(':orderId')
getOrder(@Param('orderId', ParseUUIDPipe) orderId: string) { /* ... */ }
```

## Usage Criteria Summary

| What you need to do | Component to use |
|-----------|----------------|
| Inject a Correlation ID into every request | Middleware |
| Verify an auth token | Guard |
| Role-based access control | Guard |
| Log HTTP requests/responses | Interceptor |
| Measure response time | Interceptor |
| DTO validation | Pipe (ValidationPipe) |
| Parameter type conversion | Pipe (ParseIntPipe, etc.) |

## Decorator Application-Level Rule

Apply Guard and Interceptor at the **class level**. Avoid method-level application.

```typescript
// Correct — class level
@Authenticated()
@UseInterceptors(LoggingInterceptor)
@Controller('orders')
export class OrderController { /* ... */ }

// Avoid — method level (only when there's a specific reason)
@Get(':orderId')
@Authenticated()
getOrder() { /* ... */ }
```

## Principles

- **Use the component that fits the role**: authorization is Guard, logging is Interceptor, validation is Pipe. Don't mix them.
- **Apply Guard/Interceptor at the class level**: use per-method application only as an exception.
- **Middleware is for common preprocessing only**: implement as Middleware only logic that applies to every request, such as Correlation ID or body parsing.
- **Prohibited in the Domain layer**: Middleware, Guard, Interceptor, and Pipe all belong to the Interface/Infrastructure layers.
