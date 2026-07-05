# Observability — 로깅, 메트릭, 트레이싱

---

## 로그 레벨 정책

5단계 레벨을 정의하고 각 레벨의 용도를 엄격히 지킨다.

| 레벨 | 용도 | 예시 |
|------|------|------|
| `error` | 요청 처리 실패, 외부 시스템 장애 | DB 연결 실패, 외부 API 5xx, 처리되지 않은 예외 |
| `warn` | 정상 동작이지만 주의가 필요한 상황 | Deprecated 엔드포인트 호출, 재시도 발생, 임계값 근접 |
| `log` | 주요 비즈니스 이벤트, 상태 변경 | 주문 생성, 결제 완료, 앱 기동/종료 |
| `debug` | 개발/디버깅용 상세 정보 | 쿼리 파라미터, 중간 계산 결과 |
| `verbose` | 최대 상세 정보 | 전체 요청/응답 페이로드 |

**환경별 로그 레벨:**

- **프로덕션**: `error`, `warn`, `log`만 출력
- **개발/스테이징**: 전체 레벨 출력

불필요한 로그는 운영 비용 증가와 중요 로그 노이즈로 이어진다. 프로덕션에서 debug/verbose를 비활성화한다.

---

## 레이어별 로깅 기준

| 레이어 | 로깅 대상 | 레벨 |
|--------|----------|------|
| Interface (Controller) | 요청 에러 (catch 블록) | `error` |
| Application (Service) | 비즈니스 이벤트, 외부 시스템 호출 결과 | `log`, `error` |
| Infrastructure | 외부 연동 실패/재시도, 쿼리 성능 이상 | `error`, `warn`, `debug` |
| Domain | **로깅하지 않음** | — |

**Domain 레이어에서 로깅하지 않는 이유:** Domain은 프레임워크 무의존을 유지한다. 도메인 로직의 결과는 Application 레이어에서 로깅한다.

---

## 구조화된 로깅

외부 모니터링 시스템(Datadog, CloudWatch, Grafana Loki 등)과 연동할 때는 JSON 형식의 구조화된 로그를 사용한다.

### 필드 네이밍 규칙

로그 객체의 필드명은 **snake_case**를 사용한다.

```typescript
// 비즈니스 이벤트 로그
logger.log({ message: '주문 생성 완료', order_id: orderId, user_id: userId, amount })

// 에러 로그
logger.error({ message: 'SQS 전송 실패', event_id: event.eventId, error })

// HTTP 요청 로그
logger.log({ message: 'POST /orders', method: 'POST', url: '/orders', duration_ms: 42 })
```

**camelCase 대신 snake_case를 쓰는 이유:** 대부분의 모니터링 플랫폼(Datadog, CloudWatch)이 snake_case 필드를 기본으로 파싱한다. 필드명 불일치 시 인덱싱이 깨지거나 쿼리가 작동하지 않는다.

---

## Correlation ID — 분산 요청 추적

여러 서비스를 거치는 단일 요청을 로그에서 추적하기 위해 **Correlation ID**를 모든 로그에 포함한다.

### 흐름

```
클라이언트 → x-correlation-id 헤더 포함하여 요청 (없으면 서버가 생성)
         → 서비스 내 모든 로그에 correlation_id 포함
         → 다른 서비스 호출 시 x-correlation-id 헤더 전달
         → 응답에 x-correlation-id 헤더 포함
```

### 구현 원칙

Correlation ID는 요청 진입점(Interface 레이어)에서 생성/추출하여 **AsyncLocalStorage**로 전파한다. 모든 레이어가 함수 인자 없이 현재 요청의 Correlation ID에 접근할 수 있다.

```typescript
// 개념 — Correlation ID 저장소
const correlationStorage = new AsyncLocalStorage<string>()

// 요청 진입 시
const correlationId = request.headers['x-correlation-id'] ?? generateId()
correlationStorage.run(correlationId, () => handleRequest())

// 로그 사용 시
const correlationId = correlationStorage.getStore()
logger.log({ message: '...', correlation_id: correlationId })
```

**프레임워크별 구현은 `docs/implementations/` 참조.**

---

## 메트릭 · 트레이싱 (방향 메모)

본 가이드는 특정 observability 스택을 강제하지 않는다. 운영 환경에서 다음을 고려한다.

### 메트릭

- **Prometheus** 기반 `GET /metrics` 엔드포인트 + 스크레이프
- 핵심 알람 항목:
  - HTTP 5xx rate
  - p99 응답 시간
  - DB 커넥션 풀 포화
  - 메시지 큐 DLQ depth > 0
  - 메시지 큐 `ApproximateAgeOfOldestMessage`

### 트레이싱

- **OpenTelemetry** auto-instrumentation으로 HTTP / DB / 메시지 큐 span 자동 수집
- 비동기 경계(Task Queue, Integration Event)에서 `traceparent`를 outbox payload에 포함하여 trace context를 전파하면 HTTP 요청 → 이벤트 처리가 단일 trace로 연결된다.
- 로그 레코드에 `trace_id`를 포함하여 trace → log 점프가 가능하게 한다.

---

## 원칙

- **Domain 레이어에서 로깅 금지**: 도메인 로직의 결과는 Application 레이어에서 로깅한다.
- **구조화된 로그 사용**: JSON + snake_case 필드명. 문자열 보간 금지.
- **에러는 반드시 로깅**: catch 블록에서 에러를 로깅한 뒤 예외를 던진다. 조용히 삼키지 않는다.
- **Correlation ID로 요청 추적**: 분산 환경에서 모든 로그에 Correlation ID를 포함한다.
- **프로덕션에서 debug/verbose 비활성화**: 환경별 로그 레벨을 설정한다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Correlation ID 주입 위치
