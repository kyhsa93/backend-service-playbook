# 횡단 관심사 (Cross-Cutting Concerns)

인증, 로깅, 입력 검증, Correlation ID 주입 등 여러 레이어에 걸쳐 반복적으로 필요한 관심사를 처리하는 위치와 방법을 정의한다.

---

## 요청 파이프라인

```
요청 → [1. 전처리 Middleware] → [2. 인증 Guard] → [3. 입력 검증 Pipe] → [4. Handler] → [5. 응답 변환 Interceptor] → 응답
```

각 단계는 명확한 책임을 가지며 역할을 혼용하지 않는다.

| 단계 | 역할 | 예시 |
|------|------|------|
| 1. 전처리 | 모든 요청에 대한 컨텍스트 설정 | Correlation ID 주입, 바디 파싱 |
| 2. 인증 | 요청 허용/거부 결정 | JWT 토큰 검증, RBAC |
| 3. 입력 검증 | DTO 유효성 검증, 타입 변환 | 필수 필드 누락, 타입 불일치 |
| 4. Handler | 비즈니스 유스케이스 실행 | Command/Query Service 호출 |
| 5. 응답 변환 | 응답 후처리, 횡단 로깅 | HTTP 요청 로깅, 응답 시간 측정 |

---

## 관심사별 처리 위치

### Correlation ID 주입 (전처리 단계)

모든 요청에 Correlation ID를 주입한다. 클라이언트가 `x-correlation-id` 헤더를 보내면 그대로 사용하고, 없으면 서버에서 생성한다.

```typescript
// 전처리 (개념)
function correlationIdMiddleware(req, res, next) {
  const correlationId = req.headers['x-correlation-id'] ?? generateId()
  res.setHeader('x-correlation-id', correlationId)
  correlationIdStorage.run(correlationId, () => next())
}
```

→ AsyncLocalStorage로 전파하여 이후 모든 단계에서 `correlationIdStorage.getStore()`로 접근한다.

### 인증 (Guard 단계)

토큰 검증과 사용자 정보 추출은 Handler 진입 전에 처리한다. Handler(Controller 메서드)는 인증이 완료된 사용자 정보를 request 객체에서 꺼내 쓴다.

```typescript
// Guard (개념)
function authGuard(req): boolean {
  const token = req.headers['authorization']?.replace('Bearer ', '')
  if (!token) return false
  req.user = jwt.verify(token, secret)
  return true
}
```

→ Guard는 Controller 클래스 레벨에서 적용한다. 메서드 레벨 적용은 누락 위험이 있다.

### 입력 검증 (Pipe 단계)

DTO 유효성 검증은 Handler 진입 전에 수행한다. Handler는 이미 검증된 입력만 받는다.

```
필수 필드 누락, 타입 불일치, 길이 초과 → 400 Bad Request (검증 단계에서 차단)
비즈니스 규칙 위반 (이미 취소된 주문 등) → Handler 내부에서 처리
```

**입력 검증과 비즈니스 규칙을 혼동하지 않는다.** 형식적 검증(타입, 형식, 필수값)은 파이프라인 초기에, 비즈니스 규칙 검증(도메인 상태 확인)은 Domain 레이어에서 한다.

### HTTP 요청 로깅 (응답 후처리 단계)

HTTP 요청의 메서드, URL, 응답 시간을 응답 직후에 로깅한다. Handler 내부에서 로깅하지 않는다.

```typescript
// 응답 변환/로깅 (개념)
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

## Domain 레이어에서 횡단 관심사 사용 금지

Middleware, Guard, Pipe, Interceptor 등 요청 파이프라인 구성 요소는 모두 **Interface 레이어에 속한다.** Domain 레이어에서 사용하지 않는다.

```typescript
// 금지 — Domain 레이어에서 로거/프레임워크 사용
import { Logger } from '@nestjs/common'  // ← 금지

export class Order {
  private readonly logger = new Logger(Order.name)  // ← 금지
  public cancel(reason: string): void {
    this.logger.log('주문 취소')  // ← 금지
    ...
  }
}
```

---

## 원칙

- **역할에 맞는 단계를 사용**: 인증은 Guard, 로깅은 Interceptor, 검증은 Pipe. 혼용하지 않는다.
- **전처리는 최대한 앞 단계에**: Correlation ID 주입, 인증처럼 모든 요청에 필요한 것은 파이프라인 초기에 처리한다.
- **Handler는 순수하게**: Handler(Controller 메서드)는 Service 호출과 에러 변환만 담당한다. 직접 인증/검증 로직을 작성하지 않는다.
- **Guard/Interceptor는 클래스 레벨 적용**: 메서드별 적용은 예외적으로만 사용한다.

---

### 관련 문서

- [authentication.md](authentication.md) — 인증 패턴 상세
- [observability.md](observability.md) — 로깅 패턴
- [error-handling.md](error-handling.md) — 에러 변환 위치
