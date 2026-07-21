# Authentication Pattern

---

## Authentication flow

```
[Issuing a token]
Client → POST /auth/sign-in (credentials)
           → AuthService: verifies the credentials → issues an access token
           → Client: { accessToken }

[An authenticated request]
Client → includes an Authorization: Bearer <access_token> header
          → Interface layer (Guard/Filter): extracts the token from the header → verifies it
          → assigns the user info to request.user → passes it to the Handler
```

---

## Layer-placement principle

**Authentication is handled only in the Interface layer.** The Domain and Application layers never depend on the authentication context.

```
Interface layer: extracts the token → verifies it → assigns request.user
Application layer: a command/query only includes what it needs, like the userId
Domain layer: no concept of authentication. Pure business logic
```

A wrong pattern — verifying the token in the Application/Domain layer:

```typescript
// forbidden — verifying the token directly in an Application Service
public async cancelOrder(token: string, command: CancelOrderCommand) {
  const user = await this.authService.verify(token)  // ← this is the Interface layer's job
  ...
}
```

The correct pattern — the Interface layer extracts only the userId and passes it along:

```typescript
// Interface layer: extract the userId from the token and include it in the Command
public async cancelOrder(
  @Req() req: { user: { userId: string } },
  @Body() body: CancelOrderRequestBody
): Promise<void> {
  return this.commandService.cancelOrder({ ...body, userId: req.user.userId })
}
```

---

## The JWT Bearer token pattern

### Issuing a token

```typescript
// application layer (conceptual)
export class AuthService {
  public async sign(payload: { userId: string }): Promise<string> {
    return jwt.sign(payload, jwtSecret, { expiresIn: '1h' })
  }
}
```

### Verifying a token

```typescript
// interface layer (conceptual)
export class AuthGuard {
  public async canActivate(request: Request): Promise<boolean> {
    const authorization = request.headers['authorization']
    if (!authorization?.startsWith('Bearer ')) return false

    const token = authorization.replace('Bearer ', '')
    try {
      const payload = jwt.verify(token, jwtSecret) as { userId: string }
      request.user = payload
      return true
    } catch {
      return false
    }
  }
}
```

---

## Designing the token payload

Put only **the minimum amount of information** in the JWT payload.

```typescript
// correct — includes only the ID
{ userId: 'user-abc123', iat: 1234567890, exp: 1234571490 }

// wrong — includes sensitive info or info that changes often
{ userId: '...', email: '...', role: '...', permissions: [...] }
```

**Why:**
- The payload is only signed, not encrypted (it can be read by base64-decoding it)
- Roles/permissions can change after a token is issued. Putting them in the token means a change isn't reflected immediately.
- Look up whatever user info is needed from the DB at request-handling time.

---

## Distinguishing endpoints that need auth from ones that don't

```
Needs auth: @UseGuards(AuthGuard) → every domain API
Doesn't need auth: no Guard → POST /auth/sign-in, GET /health/*
```

Apply the Guard at the **Controller class level**. Applying it per-method risks missing one.

```typescript
// correct — at the class level
@UseGuards(AuthGuard)
export class OrderController { /* auth applies to every method */ }

// avoid — applying it per method (can be missed)
export class OrderController {
  @UseGuards(AuthGuard)
  getOrder() { /* ... */ }
  deleteOrder() { /* ... missing it */ }
}
```

---

### Related docs

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where auth sits in the request pipeline
- [layer-architecture.md](layer-architecture.md) — the Interface layer's role
