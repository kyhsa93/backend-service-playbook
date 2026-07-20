package main

import (
	"bytes"
	"fmt"
	"text/template"
)

// render는 tmplStr(Go text/template)을 Names로 실행해 문자열을 반환한다.
// 실행 실패는 생성기 자체의 버그이므로 panic으로 즉시 드러낸다.
func render(name, tmplStr string, n Names) string {
	t := template.Must(template.New(name).Parse(tmplStr))
	var buf bytes.Buffer
	if err := t.Execute(&buf, n); err != nil {
		panic(fmt.Sprintf("render %s: %v", name, err))
	}
	return buf.String()
}

// ---- Domain 레이어 ----

const tmplAggregate = `package {{.DomainLower}}

import (
	"time"

	"{{.ModulePath}}/internal/common"
)

// {{.Domain}}은 이 스캐폴딩 생성기(scripts/create-domain)가 만든 최소 Aggregate Root다 —
// 단일 Status 필드가 PENDING -> ACTIVE|CANCELLED로 전이한다. 실제 도메인 규칙(불변식,
// 추가 필드)은 이 골격 위에 채워 넣는다(reference.md 템플릿과 동일한 설계).
type {{.Domain}} struct {
	{{.Domain}}ID string
	OwnerID   string
	Status    Status
	CreatedAt time.Time
	events    []DomainEvent
}

// New는 신규 {{.Domain}}을 PENDING 상태로 생성한다. ID는 여기서 새로 발급한다 —
// 파라미터 목록에 ID가 없다는 것 자체가 "클라이언트가 제공한 ID를 받지 않는다"는
// 규칙을 코드로 강제한다(aggregate-id.md).
func New(ownerID string) *{{.Domain}} {
	return &{{.Domain}}{
		{{.Domain}}ID: common.NewID(),
		OwnerID:   ownerID,
		Status:    StatusPending,
		CreatedAt: time.Now(),
	}
}

// Reconstitute는 DB에서 읽은 값으로 {{.Domain}}을 복원한다. 이벤트를 발행하지 않는다.
func Reconstitute({{.DomainCamel}}ID, ownerID string, status Status, createdAt time.Time) *{{.Domain}} {
	return &{{.Domain}}{ {{.Domain}}ID: {{.DomainCamel}}ID, OwnerID: ownerID, Status: status, CreatedAt: createdAt }
}

// Activate는 이벤트를 발행하지 않는 단순 상태 전이 예시다 — 도메인 이벤트가
// 필요 없는 변경은 이렇게 상태만 바꾼다.
func ({{.Recv}} *{{.Domain}}) Activate() error {
	if {{.Recv}}.Status == StatusCancelled {
		return ErrAlreadyCancelled
	}
	{{.Recv}}.Status = StatusActive
	return nil
}

// Cancel은 {{.Domain}}을 취소한다. 이벤트를 발행하는 상태 전이 예시다 — 불변식은
// 여기서만 검증한다(Application Handler는 호출만 하고 조건을 직접 검사하지 않는다).
func ({{.Recv}} *{{.Domain}}) Cancel(reason string) error {
	if {{.Recv}}.Status == StatusCancelled {
		return ErrAlreadyCancelled
	}
	{{.Recv}}.Status = StatusCancelled
	{{.Recv}}.events = append({{.Recv}}.events, {{.Domain}}Cancelled{
		{{.Domain}}ID:   {{.Recv}}.{{.Domain}}ID,
		Reason:      reason,
		CancelledAt: time.Now(),
	})
	return nil
}

func ({{.Recv}} *{{.Domain}}) DomainEvents() []DomainEvent { return {{.Recv}}.events }
func ({{.Recv}} *{{.Domain}}) ClearEvents()                { {{.Recv}}.events = nil }
`

const tmplStatus = `package {{.DomainLower}}

type Status string

const (
	StatusPending   Status = "PENDING"
	StatusActive    Status = "ACTIVE"
	StatusCancelled Status = "CANCELLED"
)
`

const tmplEvents = `package {{.DomainLower}}

import "time"

// DomainEvent는 {{.Domain}} 도메인 이벤트가 공유하는 마커 인터페이스다. Go에는 union
// 타입이 없으므로 빈 메서드로 "이 이벤트들 중 하나"라는 관계를 표현한다(tactical-ddd.md).
type DomainEvent interface {
	is{{.Domain}}DomainEvent()
}

// {{.Domain}}Cancelled는 {{.Domain}}이 취소됐을 때 발행되는 도메인 이벤트다.
type {{.Domain}}Cancelled struct {
	{{.Domain}}ID   string
	Reason      string
	CancelledAt time.Time
}

func ({{.Domain}}Cancelled) is{{.Domain}}DomainEvent() {}
`

const tmplErrors = `package {{.DomainLower}}

import "errors"

var (
	ErrNotFound         = errors.New("{{.DomainLower}} not found")
	ErrAlreadyCancelled = errors.New("{{.DomainLower}} already cancelled")
)
`

const tmplRepository = `package {{.DomainLower}}

import "context"

type FindQuery struct {
	Page      int
	Take      int
	{{.Domain}}ID string
	OwnerID   string
}

// Query는 읽기 전용 조회 메서드만 노출하는 Query 전용 인터페이스다. Query Handler는
// 쓰기 메서드(Save{{.Domain}})에 접근할 수 없도록 이 인터페이스에만 의존해야 한다(cqrs-pattern.md).
//
// 조회는 root의 find<Noun>s 컨벤션에 맞춰 Find{{.Domain}}s 단일 메서드로 통일한다 — 단건
// 조회 전용 메서드는 두지 않는다. 호출부는 FindOne(이 패키지가 제공하는 헬퍼)로
// Find{{.Domain}}s를 Take: 1로 호출하고 첫 결과를 꺼낸다(account.FindOne과 동일한 관용구).
type Query interface {
	Find{{.Domain}}s(ctx context.Context, q FindQuery) ([]*{{.Domain}}, int, error)
}

// Repository는 Query의 읽기 메서드에 쓰기 메서드(Save{{.Domain}})를 더한 Command 전용 인터페이스다.
type Repository interface {
	Query
	Save{{.Domain}}(ctx context.Context, {{.Recv}} *{{.Domain}}) error
}

// FindOne은 단건 조회 호출부의 반복되는 패턴(Find{{.Domain}}s를 Take: 1로 호출한 뒤 첫 번째
// 결과를 꺼내고, 없으면 ErrNotFound)을 감싼 헬퍼다(account.FindOne과 동일한 관용구).
func FindOne(ctx context.Context, q Query, {{.DomainCamel}}ID, ownerID string) (*{{.Domain}}, error) {
	items, _, err := q.Find{{.Domain}}s(ctx, FindQuery{ {{.Domain}}ID: {{.DomainCamel}}ID, OwnerID: ownerID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(items) == 0 {
		return nil, ErrNotFound
	}
	return items[0], nil
}
`

// ---- Application 레이어 — Command ----

const tmplCreateHandler = `package command

import (
	"context"
	"fmt"

	"{{.ModulePath}}/internal/domain/{{.DomainLower}}"
)

type Create{{.Domain}}Command struct {
	OwnerID string
}

type Create{{.Domain}}Handler struct {
	repo {{.DomainLower}}.Repository
}

func NewCreate{{.Domain}}Handler(repo {{.DomainLower}}.Repository) *Create{{.Domain}}Handler {
	return &Create{{.Domain}}Handler{repo: repo}
}

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기
// 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지,
// domain-events.md).
func (h *Create{{.Domain}}Handler) Handle(ctx context.Context, cmd Create{{.Domain}}Command) (*{{.DomainLower}}.{{.Domain}}, error) {
	{{.Recv}} := {{.DomainLower}}.New(cmd.OwnerID)
	// Repository.Save{{.Domain}}이 {{.DomainLower}} row와 Outbox row를 같은 트랜잭션으로 커밋한다(domain-events.md).
	if err := h.repo.Save{{.Domain}}(ctx, {{.Recv}}); err != nil {
		return nil, fmt.Errorf("create {{.DomainLower}}: %w", err)
	}
	return {{.Recv}}, nil
}
`

const tmplCancelHandler = `package command

import (
	"context"
	"fmt"

	"{{.ModulePath}}/internal/domain/{{.DomainLower}}"
)

type Cancel{{.Domain}}Command struct {
	{{.Domain}}ID string
	OwnerID   string
	Reason    string
}

type Cancel{{.Domain}}Handler struct {
	repo {{.DomainLower}}.Repository
}

func NewCancel{{.Domain}}Handler(repo {{.DomainLower}}.Repository) *Cancel{{.Domain}}Handler {
	return &Cancel{{.Domain}}Handler{repo: repo}
}

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기
// 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지,
// domain-events.md).
func (h *Cancel{{.Domain}}Handler) Handle(ctx context.Context, cmd Cancel{{.Domain}}Command) error {
	{{.Recv}}, err := {{.DomainLower}}.FindOne(ctx, h.repo, cmd.{{.Domain}}ID, cmd.OwnerID)
	if err != nil {
		return fmt.Errorf("cancel {{.DomainLower}}: %w", err)
	}

	// 비즈니스 규칙은 Aggregate 내부에서 검증 — Handler는 조율만 한다.
	if err := {{.Recv}}.Cancel(cmd.Reason); err != nil {
		return err
	}

	if err := h.repo.Save{{.Domain}}(ctx, {{.Recv}}); err != nil {
		return fmt.Errorf("cancel {{.DomainLower}}: %w", err)
	}
	return nil
}
`

// ---- Application 레이어 — Query ----

const tmplGetHandler = `package query

import (
	"context"
	"fmt"

	"{{.ModulePath}}/internal/domain/{{.DomainLower}}"
)

type Get{{.Domain}}Query struct {
	{{.Domain}}ID string
	OwnerID   string
}

// Get{{.Domain}}Handler는 읽기 전용 {{.DomainLower}}.Query에만 의존한다(쓰기 전용
// 인터페이스는 참조하지 않는다 — cqrs-pattern.md).
type Get{{.Domain}}Handler struct {
	repo {{.DomainLower}}.Query
}

func NewGet{{.Domain}}Handler(repo {{.DomainLower}}.Query) *Get{{.Domain}}Handler {
	return &Get{{.Domain}}Handler{repo: repo}
}

func (h *Get{{.Domain}}Handler) Handle(ctx context.Context, q Get{{.Domain}}Query) (*Get{{.Domain}}Result, error) {
	{{.Recv}}, err := {{.DomainLower}}.FindOne(ctx, h.repo, q.{{.Domain}}ID, q.OwnerID)
	if err != nil {
		return nil, fmt.Errorf("get {{.DomainLower}}: %w", err)
	}
	return &Get{{.Domain}}Result{
		{{.Domain}}ID: {{.Recv}}.{{.Domain}}ID,
		OwnerID:   {{.Recv}}.OwnerID,
		Status:    string({{.Recv}}.Status),
		CreatedAt: {{.Recv}}.CreatedAt,
	}, nil
}
`

const tmplResult = `package query

import "time"

type Get{{.Domain}}Result struct {
	{{.Domain}}ID string
	OwnerID   string
	Status    string
	CreatedAt time.Time
}
`

// ---- Application 레이어 — Event ----

const tmplEventHandler = `package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"{{.ModulePath}}/internal/domain/{{.DomainLower}}"
)

// {{.Domain}}CancelledEventHandler는 outbox가 드레인한 {{.Domain}}Cancelled 페이로드를
// 처리한다. 스캐폴딩 단계에서는 구조화 로깅만 한다 — 실제 알림 발송이 필요해지면
// infrastructure/notification에 전용 Notifier를 추가해 주입한다(이벤트 패키지가 이미
// 갖고 있는 account 전용 Notifier는 account.DomainEvent에 고정돼 있어 재사용할 수 없다).
type {{.Domain}}CancelledEventHandler struct{}

func New{{.Domain}}CancelledEventHandler() *{{.Domain}}CancelledEventHandler {
	return &{{.Domain}}CancelledEventHandler{}
}

// Handle은 outbox.Handler 시그니처를 만족한다 — Consumer가 SQS에서 event_type=
// "{{.Domain}}Cancelled" 메시지를 수신할 때마다 호출한다.
func (h *{{.Domain}}CancelledEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt {{.DomainLower}}.{{.Domain}}Cancelled
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal {{.Domain}}Cancelled: %w", err)
	}
	slog.InfoContext(ctx, "{{.DomainLower}} cancelled", "{{.DomainLower}}_id", evt.{{.Domain}}ID, "reason", evt.Reason)
	return nil
}
`

// ---- Infrastructure 레이어 ----

const tmplPersistence = `package persistence

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"time"

	"{{.ModulePath}}/internal/common"
	"{{.ModulePath}}/internal/domain/{{.DomainLower}}"
)

type {{.Domain}}Repository struct {
	db *sql.DB
}

// 컴파일 타임 interface 충족 검증 — {{.Domain}}Repository는 Repository(Command)와 Query
// 양쪽을 모두 만족한다(account_repository.go와 동일한 구조적 타이핑 관용구 — 구현체를
// 두 벌 둘 필요가 없다).
var _ {{.DomainLower}}.Repository = (*{{.Domain}}Repository)(nil)
var _ {{.DomainLower}}.Query = (*{{.Domain}}Repository)(nil)

func New{{.Domain}}Repository(db *sql.DB) *{{.Domain}}Repository {
	return &{{.Domain}}Repository{db: db}
}

func (r *{{.Domain}}Repository) Find{{.Domain}}s(ctx context.Context, q {{.DomainLower}}.FindQuery) ([]*{{.DomainLower}}.{{.Domain}}, int, error) {
	args := []any{}
	where := []string{"1 = 1"}
	i := 1

	if q.{{.Domain}}ID != "" {
		where = append(where, fmt.Sprintf("id = $%d", i))
		args = append(args, q.{{.Domain}}ID)
		i++
	}
	if q.OwnerID != "" {
		where = append(where, fmt.Sprintf("owner_id = $%d", i))
		args = append(args, q.OwnerID)
		i++
	}
	whereClause := strings.Join(where, " AND ")

	var total int
	if err := r.db.QueryRowContext(ctx,
		fmt.Sprintf(` + "`" + `SELECT COUNT(*) FROM {{.DomainsLower}} WHERE %s` + "`" + `, whereClause), args...,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count {{.DomainsLower}}: %w", err)
	}

	take := q.Take
	if take <= 0 {
		take = 20
	}
	args = append(args, take, q.Page*take)
	rows, err := r.db.QueryContext(ctx,
		fmt.Sprintf(` + "`" + `SELECT id, owner_id, status, created_at FROM {{.DomainsLower}}
		 WHERE %s ORDER BY id DESC LIMIT $%d OFFSET $%d` + "`" + `, whereClause, i, i+1),
		args...,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("find {{.DomainsLower}}: %w", err)
	}
	defer func() { _ = rows.Close() }()

	var items []*{{.DomainLower}}.{{.Domain}}
	for rows.Next() {
		var id, ownerID, status string
		var createdAt time.Time
		if err := rows.Scan(&id, &ownerID, &status, &createdAt); err != nil {
			return nil, 0, err
		}
		items = append(items, {{.DomainLower}}.Reconstitute(id, ownerID, {{.DomainLower}}.Status(status), createdAt))
	}
	return items, total, rows.Err()
}

// Save{{.Domain}}은 {{.Domain}} row와 Outbox row를 같은 트랜잭션으로 커밋한다(dual-write 회피,
// domain-events.md). 공유 outbox.Writer(internal/infrastructure/outbox/writer.go)는
// 현재 []account.DomainEvent에 고정된 시그니처라 다른 도메인의 이벤트 슬라이스를 그대로
// 넘길 수 없다 — Writer를 제네릭화하기 전까지는 이 트랜잭션 안에서 직접 적재한다(Poller/Consumer는
// event_type 문자열 기준으로 동작하므로 적재 방식과 무관하게 정상적으로 드레인한다).
func (r *{{.Domain}}Repository) Save{{.Domain}}(ctx context.Context, {{.Recv}} *{{.DomainLower}}.{{.Domain}}) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	_, err = tx.ExecContext(ctx,
		` + "`" + `INSERT INTO {{.DomainsLower}} (id, owner_id, status, created_at)
		 VALUES ($1, $2, $3, $4)
		 ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status` + "`" + `,
		{{.Recv}}.{{.Domain}}ID, {{.Recv}}.OwnerID, string({{.Recv}}.Status), {{.Recv}}.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("save {{.DomainLower}}: %w", err)
	}

	for _, evt := range {{.Recv}}.DomainEvents() {
		payload, err := json.Marshal(evt)
		if err != nil {
			return fmt.Errorf("marshal domain event: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			` + "`" + `INSERT INTO outbox (event_id, event_type, payload) VALUES ($1, $2, $3)` + "`" + `,
			common.NewID(), reflect.TypeOf(evt).Name(), payload,
		); err != nil {
			return fmt.Errorf("save outbox event: %w", err)
		}
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit save {{.DomainLower}}: %w", err)
	}
	{{.Recv}}.ClearEvents()
	return nil
}
`

// ---- Interface 레이어 ----

const tmplDTO = `package http

import "time"

type Cancel{{.Domain}}Request struct {
	Reason string ` + "`" + `json:"reason"` + "`" + `
}

type {{.Domain}}Response struct {
	{{.Domain}}ID string    ` + "`" + `json:"{{.DomainCamel}}Id"` + "`" + `
	OwnerID   string    ` + "`" + `json:"ownerId"` + "`" + `
	Status    string    ` + "`" + `json:"status"` + "`" + `
	CreatedAt time.Time ` + "`" + `json:"createdAt"` + "`" + `
}
`

const tmplHTTPHandler = `package http

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"{{.ModulePath}}/internal/application/command"
	"{{.ModulePath}}/internal/application/query"
	"{{.ModulePath}}/internal/domain/{{.DomainLower}}"
	"{{.ModulePath}}/internal/interface/http/middleware"
)

type {{.Domain}}Handler struct {
	create{{.Domain}} *command.Create{{.Domain}}Handler
	cancel{{.Domain}} *command.Cancel{{.Domain}}Handler
	get{{.Domain}}    *query.Get{{.Domain}}Handler
}

func New{{.Domain}}Handler(
	create{{.Domain}} *command.Create{{.Domain}}Handler,
	cancel{{.Domain}} *command.Cancel{{.Domain}}Handler,
	get{{.Domain}} *query.Get{{.Domain}}Handler,
) *{{.Domain}}Handler {
	return &{{.Domain}}Handler{create{{.Domain}}: create{{.Domain}}, cancel{{.Domain}}: cancel{{.Domain}}, get{{.Domain}}: get{{.Domain}}}
}

func (h *{{.Domain}}Handler) Create{{.Domain}}(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	{{.Recv}}, err := h.create{{.Domain}}.Handle(r.Context(), command.Create{{.Domain}}Command{OwnerID: requesterID})
	if err != nil {
		write{{.Domain}}Error(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, {{.Domain}}Response{
		{{.Domain}}ID: {{.Recv}}.{{.Domain}}ID,
		OwnerID:   {{.Recv}}.OwnerID,
		Status:    string({{.Recv}}.Status),
		CreatedAt: {{.Recv}}.CreatedAt,
	})
}

func (h *{{.Domain}}Handler) Cancel{{.Domain}}(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	{{.DomainCamel}}ID := r.PathValue("id")

	var body Cancel{{.Domain}}Request
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	if err := h.cancel{{.Domain}}.Handle(r.Context(), command.Cancel{{.Domain}}Command{
		{{.Domain}}ID: {{.DomainCamel}}ID,
		OwnerID:   requesterID,
		Reason:    body.Reason,
	}); err != nil {
		write{{.Domain}}Error(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *{{.Domain}}Handler) Get{{.Domain}}(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	{{.DomainCamel}}ID := r.PathValue("id")

	result, err := h.get{{.Domain}}.Handle(r.Context(), query.Get{{.Domain}}Query{
		{{.Domain}}ID: {{.DomainCamel}}ID,
		OwnerID:   requesterID,
	})
	if err != nil {
		write{{.Domain}}Error(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, r, {{.Domain}}Response{
		{{.Domain}}ID: result.{{.Domain}}ID,
		OwnerID:   result.OwnerID,
		Status:    result.Status,
		CreatedAt: result.CreatedAt,
	})
}

// {{.DomainCamel}}ErrorMapping은 sentinel error -> (HTTP 상태 코드, client-facing 에러 코드)
// 매핑 테이블이다(account_handler.go의 accountErrorMapping과 동일한 관용구 — error-handling.md).
var {{.DomainCamel}}ErrorMapping = []struct {
	err    error
	status int
	code   string
}{
	{ {{.DomainLower}}.ErrNotFound, http.StatusNotFound, "{{.DomainScream}}_NOT_FOUND" },
	{ {{.DomainLower}}.ErrAlreadyCancelled, http.StatusBadRequest, "{{.DomainScream}}_ALREADY_CANCELLED" },
}

func write{{.Domain}}Error(w http.ResponseWriter, r *http.Request, err error) {
	for _, m := range {{.DomainCamel}}ErrorMapping {
		if errors.Is(err, m.err) {
			writeJSONError(w, r, m.status, m.code, err.Error())
			return
		}
	}
	slog.ErrorContext(r.Context(), "unhandled {{.DomainLower}} error", "error", err)
	writeJSONError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error")
}
`

const tmplMigration = `CREATE TABLE {{.DomainsLower}} (
  id          VARCHAR(32)  PRIMARY KEY,
  owner_id    VARCHAR(32)  NOT NULL,
  status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_{{.DomainsLower}}_owner_id ON {{.DomainsLower}} (owner_id);
`

// GenerateFiles는 대상 앱 루트(예: examples/) 기준 상대 경로 -> 파일 내용 맵을 만든다.
// migrationSeq는 migrations/000N_*.sql의 다음 번호다(호출부가 기존 migrations/ 디렉토리를
// 스캔해 넘긴다).
func GenerateFiles(n Names, migrationSeq int) map[string]string {
	files := map[string]string{}

	domainDir := fmt.Sprintf("internal/domain/%s", n.DomainLower)
	files[fmt.Sprintf("%s/%s.go", domainDir, n.DomainSnake)] = render("aggregate", tmplAggregate, n)
	files[fmt.Sprintf("%s/%s_status.go", domainDir, n.DomainSnake)] = render("status", tmplStatus, n)
	files[fmt.Sprintf("%s/events.go", domainDir)] = render("events", tmplEvents, n)
	files[fmt.Sprintf("%s/errors.go", domainDir)] = render("errors", tmplErrors, n)
	files[fmt.Sprintf("%s/repository.go", domainDir)] = render("repository", tmplRepository, n)

	files[fmt.Sprintf("internal/application/command/create_%s_handler.go", n.DomainSnake)] = render("create_handler", tmplCreateHandler, n)
	files[fmt.Sprintf("internal/application/command/cancel_%s_handler.go", n.DomainSnake)] = render("cancel_handler", tmplCancelHandler, n)

	files[fmt.Sprintf("internal/application/query/get_%s_handler.go", n.DomainSnake)] = render("get_handler", tmplGetHandler, n)
	files[fmt.Sprintf("internal/application/query/%s_result.go", n.DomainSnake)] = render("result", tmplResult, n)

	files[fmt.Sprintf("internal/application/event/%s_cancelled_event_handler.go", n.DomainSnake)] = render("event_handler", tmplEventHandler, n)

	files[fmt.Sprintf("internal/infrastructure/persistence/%s_repository.go", n.DomainSnake)] = render("persistence", tmplPersistence, n)

	files[fmt.Sprintf("internal/interface/http/%s_dto.go", n.DomainSnake)] = render("dto", tmplDTO, n)
	files[fmt.Sprintf("internal/interface/http/%s_handler.go", n.DomainSnake)] = render("http_handler", tmplHTTPHandler, n)

	files[fmt.Sprintf("migrations/%04d_add_%s.sql", migrationSeq, n.DomainSnake)] = render("migration", tmplMigration, n)

	return files
}
