# 인증 패턴

---

## 인증 흐름

```
[토큰 발급]
클라이언트 → POST /auth/sign-in (credentials)
           → AuthService: 인증 정보 검증 → Access Token 발급
           → 클라이언트: { accessToken }

[인증 요청]
클라이언트 → Authorization: Bearer <access_token> 헤더 포함
          → Interface 레이어 (Guard/Filter): 헤더에서 토큰 추출 → 검증
          → request.user에 사용자 정보 할당 → Handler로 전달
```

---

## 레이어 배치 원칙

**인증은 Interface 레이어에서만 처리한다.** Domain 레이어와 Application 레이어는 인증 컨텍스트에 의존하지 않는다.

```
Interface 레이어: 토큰 추출 → 검증 → request.user 할당
Application 레이어: command/query에 userId 등 필요한 정보만 포함
Domain 레이어: 인증 개념 없음. 순수 비즈니스 로직
```

잘못된 패턴 — Application/Domain 레이어에서 토큰 검증:

```typescript
// 금지 — Application Service에서 토큰 직접 검증
public async cancelOrder(token: string, command: CancelOrderCommand) {
  const user = await this.authService.verify(token)  // ← Interface 레이어 역할
  ...
}
```

올바른 패턴 — Interface 레이어에서 userId만 추출해서 전달:

```typescript
// Interface 레이어: 토큰에서 userId 추출 후 Command에 포함
public async cancelOrder(
  @Req() req: { user: { userId: string } },
  @Body() body: CancelOrderRequestBody
): Promise<void> {
  return this.commandService.cancelOrder({ ...body, userId: req.user.userId })
}
```

---

## JWT Bearer 토큰 패턴

### 토큰 발급

```typescript
// application layer (개념)
export class AuthService {
  public async sign(payload: { userId: string }): Promise<string> {
    return jwt.sign(payload, jwtSecret, { expiresIn: '1h' })
  }
}
```

### 토큰 검증

```typescript
// interface layer (개념)
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

## 토큰 payload 설계

JWT payload에는 **최소한의 정보**만 담는다.

```typescript
// 올바른 방식 — ID만 포함
{ userId: 'user-abc123', iat: 1234567890, exp: 1234571490 }

// 잘못된 방식 — 민감 정보 또는 자주 변하는 정보 포함
{ userId: '...', email: '...', role: '...', permissions: [...] }
```

**이유:**
- payload는 서명만 되고 암호화되지 않는다 (base64 디코딩으로 읽을 수 있음)
- 역할/권한은 토큰 발급 후 변경될 수 있다. 토큰에 담으면 변경이 즉시 반영되지 않는다.
- 필요한 사용자 정보는 Request 처리 시점에 DB에서 조회한다.

---

## 인증 필요/불필요 엔드포인트 구분

```
인증 필요: @UseGuards(AuthGuard) → 모든 도메인 API
인증 불필요: Guard 없음 → POST /auth/sign-in, GET /health/*
```

Guard는 **Controller 클래스 레벨**에서 적용한다. 메서드 레벨은 누락 위험이 있다.

```typescript
// 올바른 방식 — 클래스 레벨
@UseGuards(AuthGuard)
export class OrderController { /* 모든 메서드에 인증 적용 */ }

// 지양 — 메서드별 적용 (누락 가능)
export class OrderController {
  @UseGuards(AuthGuard)
  getOrder() { /* ... */ }
  deleteOrder() { /* ... 누락 */ }
}
```

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 요청 파이프라인에서 인증 위치
- [layer-architecture.md](layer-architecture.md) — Interface 레이어 역할
