package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/lib/pq"
	"golang.org/x/time/rate"

	// Blank-imported so its init() registers the generated OpenAPI spec with
	// swag's global registry (docs/architecture/api-documentation.md).
	// Regenerate with `swag init` (see the Makefile) after changing any
	// @-annotation below or on a handler in internal/interface/http/.
	_ "github.com/example/account-service/docs"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/event"
	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/config"
	"github.com/example/account-service/internal/infrastructure/acl"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/infrastructure/database"
	"github.com/example/account-service/internal/infrastructure/llm"
	"github.com/example/account-service/internal/infrastructure/logging"
	"github.com/example/account-service/internal/infrastructure/ml"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
	"github.com/example/account-service/internal/infrastructure/persistence"
	"github.com/example/account-service/internal/infrastructure/scheduling"
	"github.com/example/account-service/internal/infrastructure/secret"
	taskqueue "github.com/example/account-service/internal/infrastructure/task-queue"
	httphandler "github.com/example/account-service/internal/interface/http"
	taskinterface "github.com/example/account-service/internal/interface/task"
)

// @title			Account Service API
// @version		0.1.0
// @description	API documentation for the DDD-based Account domain example service (Account/Card/Payment/Refund/Credential Bounded Contexts). Generated from the @-annotations above each handler in internal/interface/http/ by swag (docs/architecture/api-documentation.md) — never hand-edited.
// @BasePath		/
// @securityDefinitions.apikey	BearerAuth
// @in								header
// @name							Authorization
// @description					Type "Bearer" followed by a space and the JWT access token issued by `POST /auth/sign-in`.
func main() {
	// CorrelationHandler wraps JSONHandler so that, whenever the ctx carries a
	// correlation ID, it automatically adds a "correlation_id" field to every
	// log call — call sites never need to pass it in themselves
	// (observability.md).
	jsonHandler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
	slog.SetDefault(slog.New(logging.NewCorrelationHandler(jsonHandler)))

	dbConfig, err := config.LoadDatabaseConfig()
	if err != nil {
		slog.Error("config error", "error", err)
		os.Exit(1)
	}

	db, err := sql.Open("postgres", dbConfig.URL)
	if err != nil {
		slog.Error("failed to connect to db", "error", err)
		os.Exit(1)
	}
	defer func() {
		if closeErr := db.Close(); closeErr != nil {
			slog.Error("failed to close db", "error", closeErr)
		}
	}()

	// Dependency assembly — constructor chaining, no framework
	notifier := notification.NewService(notification.NewSESClient(), db)

	sqsConfig, err := config.LoadSQSConfig()
	if err != nil {
		slog.Error("config error", "error", err)
		os.Exit(1)
	}

	outboxWriter := outbox.NewWriter()
	outboxPublisher := outbox.NewPublisher(db)
	sqsClient := outbox.NewSQSClient()

	// Card BC dependencies. Only the composition root (main) knows about both
	// Account and Card, and the two BCs never import each other — Account
	// writes Integration Events to the Outbox under a string event_type, and
	// Card subscribes to that string in the handler map below
	// (cross-domain.md).
	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	// dbManager satisfies the command.TransactionManager port — used only
	// when a Handler like TransferHandler needs to atomically group two or
	// more SaveAccount calls (for standalone call sites elsewhere, a local
	// transaction that SaveAccount itself opens and closes, as it does now,
	// is sufficient).
	dbManager := database.NewManager(db)
	cardRepo := persistence.NewCardRepository(db)
	credentialRepo := persistence.NewCredentialRepository(db)
	accountAdapter := acl.NewAccountAdapter(accountRepo)
	suspendCardsHandler := command.NewSuspendCardsByAccountHandler(cardRepo)
	cancelCardsHandler := command.NewCancelCardsByAccountHandler(cardRepo)

	// Payment BC dependencies — as with Account/Card, only the composition
	// root knows about all three BCs, and they are connected to each other
	// only via the Outbox's string event_type. Payment looks up Card/Account
	// synchronously via an Adapter (ACL) (paymentCardAdapter/
	// paymentAccountAdapter), and Account reacts asynchronously to Payment's
	// Integration Events (payment.completed.v1, etc.) (handler map below).
	paymentRepo := persistence.NewPaymentRepository(db)
	paymentCardAdapter := acl.NewPaymentCardAdapter(cardRepo)
	paymentAccountAdapter := acl.NewPaymentAccountAdapter(accountRepo)
	withdrawByPaymentHandler := command.NewWithdrawByPaymentHandler(accountRepo)
	depositByPaymentHandler := command.NewDepositByPaymentHandler(accountRepo)

	// This handlers map is used by outbox.Consumer to look up a handler by
	// the eventType of a message received from SQS — Command Handlers no
	// longer reference this map at all (no synchronous draining,
	// domain-events.md).
	outboxHandlers := map[string]outbox.Handler{
		"AccountCreated":     event.NewAccountCreatedEventHandler(notifier).Handle,
		"MoneyDeposited":     event.NewMoneyDepositedEventHandler(notifier).Handle,
		"MoneyWithdrawn":     event.NewMoneyWithdrawnEventHandler(notifier).Handle,
		"AccountSuspended":   event.NewAccountSuspendedEventHandler(notifier, outboxPublisher).Handle,
		"AccountReactivated": event.NewAccountReactivatedEventHandler(notifier).Handle,
		"AccountClosed":      event.NewAccountClosedEventHandler(notifier, outboxPublisher).Handle,
		// Converts a Payment BC Domain Event into an Integration Event for
		// external BCs and writes it back to the Outbox (the same idiom as
		// account.AccountSuspended being converted into account.suspended.v1).
		"PaymentCompleted": event.NewPaymentCompletedEventHandler(outboxPublisher).Handle,
		"PaymentCancelled": event.NewPaymentCancelledEventHandler(outboxPublisher).Handle,
		"RefundApproved":   event.NewRefundApprovedEventHandler(outboxPublisher).Handle,
		// A Domain Event raised by the daily interest payment batch (Task
		// Queue). This is a separate flow from the Task Queue
		// (account.apply-interest, taskHandlers map below) — this event is
		// ordinary Domain Event consumption that emails the fact that
		// "interest was paid" (scheduling.md, "Task vs Domain Event
		// distinction").
		"InterestPaid": event.NewInterestPaidEventHandler(notifier).Handle,
		// The Card BC reacts to Account's Integration Events (asynchronously).
		// Only unmarshal glue lives here — the actual use case is delegated
		// to the Card Application handler.
		"account.suspended.v1": func(ctx context.Context, payload []byte) error {
			var e integrationevent.AccountSuspendedV1
			if err := json.Unmarshal(payload, &e); err != nil {
				return err
			}
			return suspendCardsHandler.Handle(ctx, command.SuspendCardsByAccountCommand{AccountID: e.AccountID})
		},
		"account.closed.v1": func(ctx context.Context, payload []byte) error {
			var e integrationevent.AccountClosedV1
			if err := json.Unmarshal(payload, &e); err != nil {
				return err
			}
			return cancelCardsHandler.Handle(ctx, command.CancelCardsByAccountCommand{AccountID: e.AccountID})
		},
		// The Account BC reacts to Payment's Integration Events
		// (asynchronously). payment.cancelled.v1 and refund.approved.v1 both
		// perform the same action — "reverse an amount that was already
		// debited" — so they reuse a single DepositByPaymentHandler (only the
		// referenceId differs, as paymentId vs refundId).
		"payment.completed.v1": func(ctx context.Context, payload []byte) error {
			var e integrationevent.PaymentCompletedV1
			if err := json.Unmarshal(payload, &e); err != nil {
				return err
			}
			return withdrawByPaymentHandler.Handle(ctx, command.WithdrawByPaymentCommand{
				AccountID: e.AccountID, Amount: e.Amount, ReferenceID: e.PaymentID,
			})
		},
		"payment.cancelled.v1": func(ctx context.Context, payload []byte) error {
			var e integrationevent.PaymentCancelledV1
			if err := json.Unmarshal(payload, &e); err != nil {
				return err
			}
			return depositByPaymentHandler.Handle(ctx, command.DepositByPaymentCommand{
				AccountID: e.AccountID, Amount: e.Amount, ReferenceID: e.PaymentID,
			})
		},
		"refund.approved.v1": func(ctx context.Context, payload []byte) error {
			var e integrationevent.RefundApprovedV1
			if err := json.Unmarshal(payload, &e); err != nil {
				return err
			}
			return depositByPaymentHandler.Handle(ctx, command.DepositByPaymentCommand{
				AccountID: e.AccountID, Amount: e.Amount, ReferenceID: e.RefundID,
			})
		},
	}

	outboxPoller := outbox.NewPoller(db, sqsClient, sqsConfig.QueueURL)
	outboxConsumer := outbox.NewConsumer(sqsClient, sqsConfig.QueueURL, outboxHandlers)

	// Task Queue dependencies — uses a separate table/queue from the
	// Domain/Integration Events (outbox, above) (this preserves the "Task
	// Queue vs Domain Event" distinction from scheduling.md/domain-events.md
	// all the way down into infrastructure). The SQS client itself is a
	// low-level object that only handles region/credentials, so it's fine to
	// share it with outbox — only the queue URL differs.
	interestConfig := config.LoadInterestConfig()
	taskWriter := taskqueue.NewWriter(db)
	interestScheduler := scheduling.NewInterestScheduler(taskWriter)
	statementScheduler := scheduling.NewStatementScheduler(taskWriter)

	applyInterestHandler := command.NewApplyDailyInterestHandler(accountRepo, interestConfig.DailyRate)
	cardPaymentAdapter := acl.NewCardPaymentAdapter(paymentRepo)
	sendStatementHandler := command.NewSendCardUsageStatementHandler(cardRepo, accountAdapter, cardPaymentAdapter, notifier)

	interestTaskController := taskinterface.NewInterestTaskController(applyInterestHandler)
	statementTaskController := taskinterface.NewStatementTaskController(sendStatementHandler)

	// taskqueue.Consumer looks up a Task Controller in this map by the
	// taskType string and calls it (the same routing idiom as
	// outboxHandlers).
	taskHandlers := map[string]taskqueue.Handler{
		"account.apply-interest":    interestTaskController.HandleApplyInterest,
		"card.send-usage-statement": statementTaskController.HandleSendStatement,
	}
	taskPoller := taskqueue.NewPoller(db, sqsClient, sqsConfig.TaskQueueURL)
	taskConsumer := taskqueue.NewConsumer(sqsClient, sqsConfig.TaskQueueURL, taskHandlers)

	secretService := secret.NewService(secret.NewSecretsManagerClient(), 5*time.Minute)
	jwtSecret, err := config.LoadJWTSecret(context.Background(), secretService, os.Getenv("APP_ENV"))
	if err != nil {
		slog.Error("failed to load jwt secret", "error", err)
		os.Exit(1)
	}
	jwtService := auth.NewJWTService(jwtSecret, time.Hour)
	passwordHasher := auth.NewBcryptPasswordHasher()

	// RefundReasonClassifier (a Technical Service, domain-service.md) — talks to a self-hosted
	// Ollama instance (docker-compose.yml's ollama/ollama-init services), so unlike jwtSecret
	// above there's no Secrets Manager lookup needed, just a plain base URL.
	refundReasonClassifier := llm.NewRefundReasonClassifierImpl(config.OllamaBaseURL(), config.RefundClassifierModel())

	// RefundFraudRiskScorer (a second, independent Technical Service, domain-service.md) — two
	// concrete implementations are always constructed, but only the one config.FraudScorerMode()
	// selects is ever wired into the router below. "native" trains in-process at construction
	// (here) and needs no extra service; "http" calls the shared services/fraud-risk-scorer
	// microservice (config.FraudScorerBaseURL()).
	var fraudRiskScorer command.RefundFraudRiskScorer
	if config.FraudScorerMode() == "http" {
		fraudRiskScorer = ml.NewRefundFraudRiskScorerHTTPImpl(config.FraudScorerBaseURL())
	} else {
		fraudRiskScorer = ml.NewRefundFraudRiskScorerNativeImpl()
	}

	rateLimitConfig := config.LoadRateLimitConfig()
	limiter := rate.NewLimiter(rate.Limit(rateLimitConfig.RequestsPerSecond), rateLimitConfig.Burst)

	mux, healthHandler := httphandler.NewRouter(accountRepo, cardRepo, credentialRepo, paymentRepo, accountAdapter, paymentCardAdapter, paymentAccountAdapter, jwtService, passwordHasher, refundReasonClassifier, fraudRiskScorer, limiter, dbManager)

	srv := &http.Server{Addr: ":8080", Handler: mux}

	// ctx is cancelled on receiving SIGTERM/SIGINT — not just the HTTP
	// server, but also the Poller/Consumer background loops below are
	// stopped via this same ctx (graceful-shutdown.md).
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		slog.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server error", "error", err)
			os.Exit(1)
		}
	}()

	// Outbox → SQS publishing (Poller) and SQS → EventHandler receiving
	// (Consumer) both run periodically and independently of HTTP requests —
	// Command Handlers never reference either of them at all (no synchronous
	// draining, domain-events.md). Both stop themselves when ctx is
	// cancelled.
	go outboxPoller.Run(ctx)
	go outboxConsumer.Run(ctx)

	// The Task Queue's Poller/Consumer are also independent background loops,
	// just like outbox. The Scheduler (interest/statement) is itself yet
	// another independent goroutine that creates Tasks — it only enqueues and
	// never executes business logic (scheduling.md).
	go taskPoller.Run(ctx)
	go taskConsumer.Run(ctx)
	go interestScheduler.Run(ctx)
	go statementScheduler.Run(ctx)

	<-ctx.Done() // blocks until SIGTERM/SIGINT is received
	slog.Info("shutdown signal received")

	// Must be called before srv.Shutdown(ctx) — the orchestrator only cuts
	// off new traffic after readiness flips to 503, so readiness must be
	// failed first, before the HTTP server actually stops, for a seamless
	// cutover (graceful-shutdown.md).
	healthHandler.StartShutdown()

	// Set generously to match terminationGracePeriodSeconds (orchestrator setting).
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Waits for in-flight requests to finish while rejecting new connections.
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown failed", "error", err)
	}
	slog.Info("server stopped")
	// defer db.Close() runs after this point — DB connections are cleaned up
	// only after the HTTP server is fully closed
}
