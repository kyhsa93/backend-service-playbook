# Observability (Go) — 구조화 로깅, Correlation ID

원칙은 루트 [observability.md](../../../../docs/architecture/observability.md)를 따른다: JSON 구조화 로그, snake_case 필드명, 레이어별 로깅 기준(Domain은 로깅하지 않음), Correlation ID를 모든 로그에 포함. Go 1.21+ 표준 라이브러리 `log/slog`가 구조화 로깅을 기본 제공하므로 별도 로깅 프레임워크(Winston, Pino 상당)가 필요 없다.

**현재 코드는 이 원칙을 거의 따르지 않는다** — `main.go`와 `notification/service.go` 모두 `log.Printf`로 평문 문자열만 남긴다. 이 문서는 `log/slog` 기반 목표 구현을 제시한다.

---

## 현재 코드 — 알려진 격차

```go
// internal/infrastructure/notification/service.go — 현재
log.Printf("notification: failed to send email for event=%s recipient=%s: %v", eventType, content.recipient, err)
log.Printf("notification: email sent event=%s recipient=%s ses_message_id=%s", eventType, content.recipient, messageID)
```

`log.Printf`는 평문 텍스트만 남기고, 구조화 필드가 없어 로그 집계 시스템(CloudWatch, Datadog 등)에서 `event_type`이나 `recipient`로 검색/필터링할 수 없다. 로그 레벨 구분도 없다(모두 같은 스트림).

---

## 목표 구현 — `log/slog`

```go
// internal/infrastructure/notification/service.go — 목표 형태
import "log/slog"

func (s *Service) send(ctx context.Context, eventType string, content emailContent) error {
	output, err := s.sesClient.SendEmail(ctx, &ses.SendEmailInput{ /* ... */ })
	if err != nil {
		return fmt.Errorf("send email: %w", err)
	}

	messageID := aws.ToString(output.MessageId)
	if _, err := s.db.ExecContext(ctx, `INSERT INTO sent_emails (...) VALUES (...)`, /* ... */); err != nil {
		return fmt.Errorf("save sent email record: %w", err)
	}

	slog.InfoContext(ctx, "notification email sent",
		"event_type", eventType,
		"recipient", content.recipient,
		"ses_message_id", messageID,
	)
	return nil
}

func (s *Service) Notify(ctx context.Context, event account.DomainEvent) {
	eventType, content, ok := describe(event)
	if !ok {
		return
	}
	if err := s.send(ctx, eventType, content); err != nil {
		slog.ErrorContext(ctx, "notification email failed",
			"event_type", eventType,
			"recipient", content.recipient,
			"error", err,
		)
	}
}
```

`slog.InfoContext`/`slog.ErrorContext`는 `ctx`를 받는다 — 아래 Correlation ID 전파와 자연스럽게 연결된다. 필드는 key-value 쌍으로 넘기며, `slog`가 기본 `TextHandler` 또는 `JSONHandler`로 렌더링한다. 운영 환경에서는 `JSONHandler`를 쓴다:

```go
// cmd/server/main.go — 목표 형태, 기동 초기에 한 번 설정
logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
	Level: slog.LevelInfo, // 프로덕션: Info 이상만. 개발: slog.LevelDebug
}))
slog.SetDefault(logger)
```

출력 예시(JSON, snake_case 필드):

```json
{"time":"2026-07-06T10:00:00Z","level":"INFO","msg":"notification email sent","event_type":"MoneyDeposited","recipient":"a@example.com","ses_message_id":"010001..."}
```

`slog`의 키를 root 규칙대로 snake_case로 직접 쓰면(`"event_type"`, `"ses_message_id"`) 별도 변환 없이 규칙을 지킨다 — Go는 camelCase가 기본 관용이지만, **로그 필드 키는 문자열 리터럴이라 관용을 벗어나 snake_case로 명시적으로 골라 쓸 수 있다.**

---

## 로그 레벨 매핑

| root 레벨 | `log/slog` 대응 |
|---|---|
| `error` | `slog.LevelError` / `slog.ErrorContext` |
| `warn` | `slog.LevelWarn` / `slog.WarnContext` |
| `log`(주요 비즈니스 이벤트) | `slog.LevelInfo` / `slog.InfoContext` |
| `debug` | `slog.LevelDebug` / `slog.DebugContext` |
| `verbose` | 별도 레벨 없음 — `slog.LevelDebug`에 통합하거나 커스텀 레벨 상수 정의(`const LevelVerbose = slog.Level(-8)`) |

프로덕션에서는 `HandlerOptions{Level: slog.LevelInfo}`로 Debug 이하를 걸러낸다. 환경별 레벨은 [config.md](config.md)의 설정 구조체에서 관리한다.

---

## 레이어별 로깅 기준

| 레이어 | 로깅 대상 | 이 저장소의 현재 상태 |
|---|---|---|
| `internal/domain/account/` | **로깅하지 않음** | 실제로 로깅 코드 없음 — 원칙 준수. `import "log"`가 없다 |
| `internal/application/command,query/` | 비즈니스 이벤트, 외부 호출 결과 | 현재 로깅 없음 — Handler에 `slog.InfoContext(ctx, "account created", "account_id", a.AccountID)` 같은 로그를 추가할 여지 있음 |
| `internal/infrastructure/` | 외부 연동 실패/재시도 | `notification/service.go`만 `log.Printf` 사용 중(위 목표 구현으로 교체 대상) |
| `internal/interface/http/` | 요청 에러 | `writeAccountError`가 `http.Error`로 클라이언트에는 응답하지만 서버 로그는 남기지 않음 — 알려진 격차, 아래 참고 |

`account_handler.go`의 `writeAccountError`가 500 에러를 클라이언트에 반환할 때 서버 측 로그를 남기지 않는 것도 격차다. 목표 패턴:

```go
func writeAccountError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, account.ErrNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	// ...
	default:
		slog.ErrorContext(r.Context(), "unhandled account error", "error", err)
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}
}
```

---

## Correlation ID — `context.Context`로 전파

root는 AsyncLocalStorage(Node)/ThreadLocal(Java) 상당의 컨텍스트-로컬 저장소로 전파하라고 한다. Go의 답은 그 자체가 목적인 표준 메커니즘, `context.Context` 값 전파다 — 별도 라이브러리가 필요 없다.

```go
// internal/interface/http/middleware.go — 목표 형태 (cross-cutting-concerns.md와 공유)
type correlationIDKey struct{}

func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString()
		}
		ctx := context.WithValue(r.Context(), correlationIDKey{}, correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func CorrelationIDFromContext(ctx context.Context) string {
	if id, ok := ctx.Value(correlationIDKey{}).(string); ok {
		return id
	}
	return ""
}
```

로그를 남길 때 `ctx`에서 꺼내 필드로 포함시킨다:

```go
slog.InfoContext(ctx, "account created",
	"account_id", a.AccountID,
	"correlation_id", CorrelationIDFromContext(ctx),
)
```

더 나아가 `slog.Handler`를 감싸서 `ctx`에 correlation ID가 있으면 **모든 로그 호출에 자동으로 필드를 추가**하는 커스텀 핸들러를 만들 수도 있다 — 매 로그 호출마다 `"correlation_id", ...`를 반복하지 않아도 된다. 현재 이 저장소에는 미들웨어 체인 자체가 없으므로([cross-cutting-concerns.md](cross-cutting-concerns.md) 참고) 이 부분은 통째로 미구현 상태다.

---

## 메트릭 · 트레이싱

root와 동일하게 이 문서도 특정 스택을 강제하지 않는다. Go 생태계에서는 `prometheus/client_golang`(메트릭)과 `go.opentelemetry.io/otel`(트레이싱)이 사실상 표준이다 — `go.mod`를 보면 이미 `go.opentelemetry.io/otel` 계열 패키지가 testcontainers-go의 간접 의존성으로 들어와 있지만, 애플리케이션 코드 자체는 아직 계측하지 않는다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어별 책임 분리(로깅 위치의 근거)
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Correlation ID 미들웨어의 위치
- [error-handling.md](error-handling.md) — 에러를 로그로 남기는 시점과 HTTP 응답의 관계
