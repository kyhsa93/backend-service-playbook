# Observability (Go) — 구조화 로깅, Correlation ID

원칙은 루트 [observability.md](../../../../docs/architecture/observability.md)를 따른다: JSON 구조화 로그, snake_case 필드명, 레이어별 로깅 기준(Domain은 로깅하지 않음), Correlation ID를 모든 로그에 포함. Go 1.21+ 표준 라이브러리 `log/slog`가 구조화 로깅을 기본 제공하므로 별도 로깅 프레임워크(Winston, Pino 상당)가 필요 없다.

`main.go`와 `notification/service.go`가 `log/slog` 기반 구조화 로깅을 쓴다.

---

## `log/slog` — 실제 구현

```go
// internal/infrastructure/notification/service.go — 실제 코드
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

// Notify는 발송 실패를 로그가 아니라 에러로 반환한다 — 호출부인 outbox/relay.go의
// Drain 루프가 이 에러를 받아 slog.ErrorContext로 로그를 남기고, 해당 이벤트를
// outbox 테이블에 미처리 상태로 남겨 다음 Drain 때 재시도한다(domain-events.md 참고).
func (s *Service) Notify(ctx context.Context, event account.DomainEvent) error {
	eventType, content, ok := describe(event)
	if !ok {
		return nil
	}
	if err := s.send(ctx, eventType, content); err != nil {
		return fmt.Errorf("notify %s: %w", eventType, err)
	}
	return nil
}
```

```go
// internal/infrastructure/outbox/relay.go — 실제 코드, Notify()가 반환한 에러를 여기서 로깅
slog.ErrorContext(ctx, "outbox event processing failed",
	"event_type", row.eventType,
	"event_id", row.eventID,
	"error", err,
)
```

`slog.InfoContext`/`slog.ErrorContext`는 `ctx`를 받는다 — 아래 Correlation ID 전파와 자연스럽게 연결된다. 필드는 key-value 쌍으로 넘기며, `slog`가 기본 `TextHandler` 또는 `JSONHandler`로 렌더링한다. 운영 환경에서는 `JSONHandler`를 쓴다:

```go
// cmd/server/main.go — 실제 코드, 기동 초기에 한 번 설정
slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))
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
| `internal/infrastructure/` | 외부 연동 실패/재시도 | `notification/service.go`(발송 성공 로그), `outbox/relay.go`(처리 실패 로그) 모두 `slog` 사용 |
| `internal/interface/http/` | 요청 에러 | `writeAccountError`가 500 에러 시 서버 측 로그도 남긴다(아래 참고) |

`account_handler.go`의 `writeAccountError`는 500 에러를 클라이언트에 반환할 때 서버 측 로그도 남긴다:

```go
// internal/interface/http/account_handler.go — 실제 코드
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

root는 AsyncLocalStorage(Node)/ThreadLocal(Java) 상당의 컨텍스트-로컬 저장소로 전파하라고 한다. Go의 답은 그 자체가 목적인 표준 메커니즘, `context.Context` 값 전파다 — 별도 라이브러리가 필요 없다. 아래는 실제 코드다.

```go
// internal/interface/http/middleware/correlation_id_middleware.go — 실제 코드 (cross-cutting-concerns.md와 공유)
func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString()
		}
		ctx := common.WithCorrelationID(r.Context(), correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func CorrelationIDFromContext(ctx context.Context) string {
	return common.CorrelationIDFromContext(ctx)
}
```

context 값을 담는 키는 `internal/interface/http/middleware`가 아니라 `internal/common`(프레임워크 무의존, 어느 레이어에서도 참조 가능한 패키지)의 `WithCorrelationID`/`CorrelationIDFromContext`에 있다 — 아래 커스텀 `slog.Handler`도 같은 패키지의 함수로 값을 읽기 때문이다.

## `slog.Handler`를 감싸 모든 로그에 자동으로 필드 추가

각 로그 호출부에서 매번 `"correlation_id", CorrelationIDFromContext(ctx)`를 넘기지 않도록, `slog.Handler`를 감싸서 `ctx`에 correlation ID가 있으면 **모든 로그 레코드에 자동으로 필드를 추가**하는 커스텀 핸들러를 둔다:

```go
// internal/infrastructure/logging/correlation.go — 실제 코드
package logging

type CorrelationHandler struct {
	slog.Handler
}

func NewCorrelationHandler(next slog.Handler) *CorrelationHandler {
	return &CorrelationHandler{Handler: next}
}

func (h *CorrelationHandler) Handle(ctx context.Context, record slog.Record) error {
	if correlationID := common.CorrelationIDFromContext(ctx); correlationID != "" {
		record.AddAttrs(slog.String("correlation_id", correlationID))
	}
	return h.Handler.Handle(ctx, record)
}

func (h *CorrelationHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithAttrs(attrs)}
}

func (h *CorrelationHandler) WithGroup(name string) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithGroup(name)}
}
```

`main.go`의 로거 초기화에서 `JSONHandler`를 이 핸들러로 감싼다:

```go
// cmd/server/main.go — 실제 코드
jsonHandler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
slog.SetDefault(slog.New(logging.NewCorrelationHandler(jsonHandler)))
```

이후 `slog.InfoContext(ctx, "account created", "account_id", a.AccountID)`처럼 `correlation_id`를 직접 넘기지 않아도, `ctx`에 값이 있으면 `CorrelationHandler.Handle`이 자동으로 필드를 추가한다. `WithAttrs`/`WithGroup`을 감싸는 이유는, 그렇지 않으면 `slog.Logger.With(...)`/`WithGroup(...)`으로 만든 하위 로거가 correlation_id 자동 주입을 잃어버리기 때문이다.

---

## 메트릭 · 트레이싱

root와 동일하게 이 문서도 특정 스택을 강제하지 않는다. Go 생태계에서는 `prometheus/client_golang`(메트릭)과 `go.opentelemetry.io/otel`(트레이싱)이 사실상 표준이다 — `go.mod`를 보면 이미 `go.opentelemetry.io/otel` 계열 패키지가 testcontainers-go의 간접 의존성으로 들어와 있지만, 애플리케이션 코드 자체는 아직 계측하지 않는다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어별 책임 분리(로깅 위치의 근거)
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Correlation ID 미들웨어의 위치
- [error-handling.md](error-handling.md) — 에러를 로그로 남기는 시점과 HTTP 응답의 관계
