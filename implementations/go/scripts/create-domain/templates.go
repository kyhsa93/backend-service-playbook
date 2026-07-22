package main

import (
	"bytes"
	"fmt"
	"text/template"
)

// render executes tmplStr (a Go text/template) with Names and returns the
// result string. An execution failure means the generator itself has a bug, so
// it's surfaced immediately via panic.
func render(name, tmplStr string, n Names) string {
	t := template.Must(template.New(name).Parse(tmplStr))
	var buf bytes.Buffer
	if err := t.Execute(&buf, n); err != nil {
		panic(fmt.Sprintf("render %s: %v", name, err))
	}
	return buf.String()
}

// ---- Domain layer ----

const tmplAggregate = `package {{.DomainLower}}

import (
	"time"

	"{{.ModulePath}}/internal/common"
)

// {{.Domain}} is the minimal Aggregate Root created by this scaffolding
// generator (scripts/create-domain) — a single Status field transitions
// PENDING -> ACTIVE|CANCELLED. Fill in the actual domain rules (invariants,
// additional fields) on top of this skeleton (same design as the reference.md
// template).
type {{.Domain}} struct {
	{{.Domain}}ID string
	OwnerID   string
	Status    Status
	CreatedAt time.Time
	events    []DomainEvent
}

// New creates a new {{.Domain}} in the PENDING state. The ID is issued fresh
// here — the parameter list having no ID param is itself how the code enforces
// the rule "never accept a client-supplied ID" (aggregate-id.md).
func New(ownerID string) *{{.Domain}} {
	return &{{.Domain}}{
		{{.Domain}}ID: common.NewID(),
		OwnerID:   ownerID,
		Status:    StatusPending,
		CreatedAt: time.Now(),
	}
}

// Reconstitute rebuilds a {{.Domain}} from values read from the DB. Doesn't emit events.
func Reconstitute({{.DomainCamel}}ID, ownerID string, status Status, createdAt time.Time) *{{.Domain}} {
	return &{{.Domain}}{ {{.Domain}}ID: {{.DomainCamel}}ID, OwnerID: ownerID, Status: status, CreatedAt: createdAt }
}

// Activate is an example of a plain state transition that doesn't emit an
// event — changes that don't need a domain event just flip the state like this.
func ({{.Recv}} *{{.Domain}}) Activate() error {
	if {{.Recv}}.Status == StatusCancelled {
		return ErrAlreadyCancelled
	}
	{{.Recv}}.Status = StatusActive
	return nil
}

// Cancel cancels the {{.Domain}}. An example of a state transition that emits
// an event — invariants are validated only here (the Application Handler only
// calls this and never checks the condition itself).
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

// DomainEvent is the marker interface shared by {{.Domain}} domain events. Go
// has no union types, so an empty method expresses the "one of these events"
// relationship (tactical-ddd.md).
type DomainEvent interface {
	is{{.Domain}}DomainEvent()
}

// {{.Domain}}Cancelled is the domain event emitted when a {{.Domain}} is cancelled.
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

// Query is a read-only interface exposing only query methods. Query Handlers
// must depend only on this interface so they can never reach the write method
// (Save{{.Domain}}) (cqrs-pattern.md).
//
// Queries are unified into a single Find{{.Domain}}s method following the root's
// find<Noun>s convention — there's no dedicated single-item lookup method.
// Callers use FindOne (a helper this package provides) to call Find{{.Domain}}s
// with Take: 1 and pull out the first result (same idiom as account.FindOne).
type Query interface {
	Find{{.Domain}}s(ctx context.Context, q FindQuery) ([]*{{.Domain}}, int, error)
}

// Repository is the Command-only interface adding a write method
// (Save{{.Domain}}) on top of Query's read methods.
type Repository interface {
	Query
	Save{{.Domain}}(ctx context.Context, {{.Recv}} *{{.Domain}}) error
}

// FindOne is a helper wrapping the repeated single-item lookup pattern (call
// Find{{.Domain}}s with Take: 1, pull out the first result, and return
// ErrNotFound if there is none) (same idiom as account.FindOne).
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

// ---- Application layer — Command ----

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

// Handle returns immediately after saving — publishing/receiving from Outbox →
// SQS is solely the responsibility of the independently, periodically running
// outbox.Poller/outbox.Consumer (synchronous draining is prohibited,
// domain-events.md).
func (h *Create{{.Domain}}Handler) Handle(ctx context.Context, cmd Create{{.Domain}}Command) (*{{.DomainLower}}.{{.Domain}}, error) {
	{{.Recv}} := {{.DomainLower}}.New(cmd.OwnerID)
	// Repository.Save{{.Domain}} commits the {{.DomainLower}} row and the Outbox row in the same transaction (domain-events.md).
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

// Handle returns immediately after saving — publishing/receiving from Outbox →
// SQS is solely the responsibility of the independently, periodically running
// outbox.Poller/outbox.Consumer (synchronous draining is prohibited,
// domain-events.md).
func (h *Cancel{{.Domain}}Handler) Handle(ctx context.Context, cmd Cancel{{.Domain}}Command) error {
	{{.Recv}}, err := {{.DomainLower}}.FindOne(ctx, h.repo, cmd.{{.Domain}}ID, cmd.OwnerID)
	if err != nil {
		return fmt.Errorf("cancel {{.DomainLower}}: %w", err)
	}

	// Business rules are validated inside the Aggregate — the Handler only coordinates.
	if err := {{.Recv}}.Cancel(cmd.Reason); err != nil {
		return err
	}

	if err := h.repo.Save{{.Domain}}(ctx, {{.Recv}}); err != nil {
		return fmt.Errorf("cancel {{.DomainLower}}: %w", err)
	}
	return nil
}
`

// ---- Application layer — Query ----

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

// Get{{.Domain}}Handler depends only on the read-only {{.DomainLower}}.Query (it
// never references the write interface — cqrs-pattern.md).
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

// ---- Application layer — Event ----

const tmplEventHandler = `package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"{{.ModulePath}}/internal/domain/{{.DomainLower}}"
)

// {{.Domain}}CancelledEventHandler processes the {{.Domain}}Cancelled payload
// drained by the outbox. At the scaffolding stage it only does structured
// logging — once actual notification sending is needed, add a dedicated
// Notifier under infrastructure/notification and inject it (the account-only
// Notifier already in the event package is fixed to account.DomainEvent, so it
// can't be reused).
type {{.Domain}}CancelledEventHandler struct{}

func New{{.Domain}}CancelledEventHandler() *{{.Domain}}CancelledEventHandler {
	return &{{.Domain}}CancelledEventHandler{}
}

// Handle satisfies the outbox.Handler signature — called every time the
// Consumer receives an event_type="{{.Domain}}Cancelled" message from SQS.
func (h *{{.Domain}}CancelledEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt {{.DomainLower}}.{{.Domain}}Cancelled
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal {{.Domain}}Cancelled: %w", err)
	}
	slog.InfoContext(ctx, "{{.DomainLower}} cancelled", "{{.DomainLower}}_id", evt.{{.Domain}}ID, "reason", evt.Reason)
	return nil
}
`

// ---- Infrastructure layer ----

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

// Compile-time interface satisfaction check — {{.Domain}}Repository satisfies
// both Repository (Command) and Query (same structural-typing idiom as
// account_repository.go — no need for two separate implementations).
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

// Save{{.Domain}} commits the {{.Domain}} row and the Outbox row in the same
// transaction (avoiding dual-write, domain-events.md). The shared outbox.Writer
// (internal/infrastructure/outbox/writer.go) currently has a signature fixed to
// []account.DomainEvent, so it can't be handed another domain's event slice
// as-is — until Writer is made generic, this loads the row directly within this
// transaction instead (the Poller/Consumer operate on the event_type string, so
// they drain correctly regardless of how the row was loaded).
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

// ---- Interface layer ----

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

// {{.DomainCamel}}ErrorMapping is the sentinel error -> (HTTP status code,
// client-facing error code) mapping table (same idiom as account_handler.go's
// accountErrorMapping — error-handling.md).
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

// GenerateFiles builds a map of relative path (from the target app root, e.g.
// examples/) -> file content. migrationSeq is the next number for
// migrations/000N_*.sql (the caller scans the existing migrations/ directory
// and passes it in).
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
