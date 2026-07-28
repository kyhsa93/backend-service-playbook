package http

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus/promhttp"
	httpSwagger "github.com/swaggo/http-swagger/v2"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"golang.org/x/time/rate"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/domain/card"
	"github.com/example/account-service/internal/domain/credential"
	"github.com/example/account-service/internal/domain/payment"
	"github.com/example/account-service/internal/interface/http/middleware"
)

// tokenService combines the two ports NewRouter needs for auth wiring:
// command.TokenIssuer (for issuing a token on sign-in) and
// middleware.TokenVerifier (for verifying tokens in the auth middleware).
// internal/infrastructure/auth.JWTService already structurally satisfies both
// signatures, so the interface/ layer only accepts this interface rather than
// importing the concrete type directly (layer-architecture.md — interface/
// must not depend directly on infrastructure/). Assembling the actual
// implementation (*auth.JWTService), which can freely import infrastructure/,
// is the job of cmd/server/main.go (the composition root).
type tokenService interface {
	command.TokenIssuer
	middleware.TokenVerifier
}

// AccountStore combines account.Repository (Command) with
// account.SpendingAnalysisRepository/account.SpendingForecastRepository —
// the same concrete *persistence.AccountRepository value structurally
// satisfies all three (the same idiom PaymentStore below uses to combine
// payment.Repository/payment.RefundRepository), so the composition root
// only needs to pass one value that satisfies this one interface for the
// Account use cases and both read models.
type AccountStore interface {
	account.Repository
	account.SpendingAnalysisRepository
	account.SpendingForecastRepository
}

// PaymentStore combines the five ports the Payment BC's handlers need between
// them (Payment Repository/Query, Refund Repository/Query, the
// ops-analytics RefundReasonInsightsQuery) — the same concrete
// *persistence.PaymentRepository value structurally satisfies all of them
// (the same idiom as the account/card Repository), so the composition root
// (main) only needs to pass a value that satisfies this one interface.
type PaymentStore interface {
	payment.Repository
	payment.RefundRepository
	payment.RefundReasonInsightsQuery
}

// NewRouter assembles the request handlers. The returned *HealthHandler is
// used by main() to call StartShutdown() on SIGTERM, flipping readiness to
// failing first (graceful-shutdown.md). The caller assembles limiter (main()
// builds it via config.LoadRateLimitConfig(), while tests use a limiter with a
// much higher threshold) — this keeps production values separate from test
// values, per rate-limiting.md's "manage thresholds via environment
// variables" principle.
func NewRouter(repo AccountStore, cardRepo card.Repository, credentialRepo credential.Repository, paymentStore PaymentStore, accountAdapter command.AccountAdapter, paymentCardAdapter command.PaymentCardAdapter, paymentAccountAdapter command.PaymentAccountAdapter, jwtService tokenService, passwordHasher command.PasswordHasher, nlTranslator query.NlTransactionQueryTranslator, nlComposer query.NlTransactionAnswerComposer, limiter *rate.Limiter, txManager command.TransactionManager) (http.Handler, *HealthHandler) {
	createAccountHandler := command.NewCreateAccountHandler(repo)
	depositHandler := command.NewDepositHandler(repo)
	withdrawHandler := command.NewWithdrawHandler(repo)
	transferHandler := command.NewTransferHandler(repo, txManager)
	suspendAccountHandler := command.NewSuspendAccountHandler(repo)
	reactivateAccountHandler := command.NewReactivateAccountHandler(repo)
	closeAccountHandler := command.NewCloseAccountHandler(repo)
	getAccountHandler := query.NewGetAccountHandler(repo)
	getTransactionsHandler := query.NewGetTransactionsHandler(repo)
	// AskTransactionHistoryHandler — a structured-data RAG pipeline
	// (domain-service.md): nlTranslator/nlComposer are the two LLM-backed
	// Technical Services, and repo is reused as the plain account.Query
	// (Retrieve) port between them.
	askTransactionHistoryHandler := query.NewAskTransactionHistoryHandler(repo, nlTranslator, nlComposer)
	// repo also satisfies account.SpendingAnalysisQuery (via AccountStore),
	// so it is reused as-is rather than requiring a separate parameter.
	getSpendingAnalysisHandler := query.NewGetSpendingAnalysisHandler(repo, repo)
	// Likewise, repo also satisfies account.SpendingForecastQuery. Note that
	// ForecastSpendingHandler (the Command side, which needs the
	// SpendingForecastModel Technical Service) is never wired here — it has
	// no HTTP entry point at all, only a Task Queue one, so it's assembled
	// directly in cmd/server/main.go alongside the other Task Controllers.
	getSpendingForecastHandler := query.NewGetSpendingForecastHandler(repo, repo)

	accountHTTP := NewAccountHandler(
		createAccountHandler,
		depositHandler,
		withdrawHandler,
		transferHandler,
		suspendAccountHandler,
		reactivateAccountHandler,
		closeAccountHandler,
		getAccountHandler,
		getTransactionsHandler,
		askTransactionHistoryHandler,
		getSpendingAnalysisHandler,
		getSpendingForecastHandler,
	)
	// Card BC — on issuance, synchronously checks whether the account is
	// active via accountAdapter (ACL) (cross-domain.md).
	issueCardHandler := command.NewIssueCardHandler(cardRepo, accountAdapter)
	getCardHandler := query.NewGetCardHandler(cardRepo)
	cardHTTP := NewCardHandler(issueCardHandler, getCardHandler)

	// Payment BC — on payment, synchronously checks whether the card is
	// active, the account is active, and the balance is sufficient via
	// paymentCardAdapter/paymentAccountAdapter (ACL). The actual account
	// debit/compensating credit does not happen here — the Account BC
	// asynchronously subscribes to the payment.completed.v1/
	// payment.cancelled.v1/refund.approved.v1 Integration Events and performs
	// it (cross-domain.md).
	createPaymentHandler := command.NewCreatePaymentHandler(paymentStore, paymentCardAdapter, paymentAccountAdapter)
	cancelPaymentHandler := command.NewCancelPaymentHandler(paymentStore)
	requestRefundHandler := command.NewRequestRefundHandler(paymentStore, paymentStore)
	getPaymentHandler := query.NewGetPaymentHandler(paymentStore)
	getPaymentsHandler := query.NewGetPaymentsHandler(paymentStore)
	getRefundsHandler := query.NewGetRefundsHandler(paymentStore, paymentStore)
	getRefundReasonInsightsHandler := query.NewGetRefundReasonInsightsHandler(paymentStore)
	paymentHTTP := NewPaymentHandler(
		createPaymentHandler,
		cancelPaymentHandler,
		requestRefundHandler,
		getPaymentHandler,
		getPaymentsHandler,
		getRefundsHandler,
		getRefundReasonInsightsHandler,
	)

	// Auth — jwtService already structurally satisfies
	// command.TokenIssuer (Sign(userID) (string, error)), so it is injected
	// into SignInHandler as-is.
	signUpHandler := command.NewSignUpHandler(credentialRepo, passwordHasher)
	signInHandler := command.NewSignInHandler(credentialRepo, passwordHasher, jwtService)
	authHTTP := NewAuthHandler(signUpHandler, signInHandler)
	healthHandler := NewHealthHandler()

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	protected.HandleFunc("POST /accounts/{id}/deposit", accountHTTP.Deposit)
	protected.HandleFunc("POST /accounts/{id}/withdraw", accountHTTP.Withdraw)
	protected.HandleFunc("POST /accounts/{id}/transfer", accountHTTP.Transfer)
	protected.HandleFunc("POST /accounts/{id}/suspend", accountHTTP.SuspendAccount)
	protected.HandleFunc("POST /accounts/{id}/reactivate", accountHTTP.ReactivateAccount)
	protected.HandleFunc("POST /accounts/{id}/close", accountHTTP.CloseAccount)
	protected.HandleFunc("GET /accounts/{id}", accountHTTP.GetAccount)
	protected.HandleFunc("GET /accounts/{id}/transactions", accountHTTP.GetTransactions)
	protected.HandleFunc("POST /accounts/{id}/transactions/ask", accountHTTP.AskTransactionHistory)
	protected.HandleFunc("GET /accounts/{id}/spending-analysis", accountHTTP.GetSpendingAnalysis)
	protected.HandleFunc("GET /accounts/{id}/spending-forecast", accountHTTP.GetSpendingForecast)
	protected.HandleFunc("POST /cards", cardHTTP.IssueCard)
	protected.HandleFunc("GET /cards/{cardId}", cardHTTP.GetCard)
	protected.HandleFunc("POST /payments", paymentHTTP.CreatePayment)
	protected.HandleFunc("POST /payments/{paymentId}/cancel", paymentHTTP.CancelPayment)
	protected.HandleFunc("GET /payments/{paymentId}", paymentHTTP.GetPayment)
	protected.HandleFunc("GET /payments", paymentHTTP.GetPayments)
	protected.HandleFunc("POST /payments/{paymentId}/refunds", paymentHTTP.RequestRefund)
	protected.HandleFunc("GET /payments/{paymentId}/refunds", paymentHTTP.GetRefunds)
	// Unscoped by paymentId — a cross-payment ops-analytics aggregate, not a
	// per-payment resource (see query.GetRefundReasonInsightsHandler).
	protected.HandleFunc("GET /refunds/reason-insights", paymentHTTP.GetRefundReasonInsights)

	// Routes subject to rate limiting
	limited := http.NewServeMux()
	limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/cards", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/cards/", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/payments", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/payments/", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/refunds/", middleware.RequireAuth(jwtService)(protected))
	limited.HandleFunc("POST /auth/sign-up", authHTTP.SignUp)
	limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)

	mux := http.NewServeMux()
	mux.Handle("/", middleware.RateLimit(limiter)(limited))
	// Health checks are for orchestrator probes only, so they are not wrapped
	// by the rate limit middleware.
	mux.HandleFunc("GET /health/live", healthHandler.Live)
	mux.HandleFunc("GET /health/ready", healthHandler.Ready)
	// Swagger UI + the generated OpenAPI JSON/YAML — public, unauthenticated,
	// and (like the healthcheck routes) not wrapped by the rate limit
	// middleware, since it's documentation rather than a business endpoint
	// (docs/architecture/api-documentation.md). httpSwagger.WrapHandler
	// serves index.html/doc.json/the swagger-ui asset bundle all under this
	// one prefix.
	mux.Handle("GET /docs/", httpSwagger.WrapHandler)
	// Prometheus scrape target — like health/docs, not rate-limited, and
	// deliberately not behind RequireAuth either: a scraper is assumed to
	// reach this endpoint over a trusted, non-public network path, the same
	// assumption most Prometheus deployments make (observability.md).
	mux.Handle("GET /metrics", promhttp.Handler())

	// middleware.Metrics must sit directly around mux (not further out) to
	// read net/http's own r.Pattern mutation — see its doc comment.
	// middleware.SecurityHeaders has no such constraint and could go
	// anywhere in this chain; it's placed next to Metrics/RequestLogging
	// since all three are per-request cross-cutting concerns applied to
	// every response (cross-cutting-concerns.md).
	instrumented := middleware.CorrelationID(middleware.RequestLogging(middleware.SecurityHeaders(middleware.Metrics(mux))))

	// otelhttp.NewHandler wraps everything, outermost — it starts (or
	// continues, if the caller sent a W3C traceparent header) a span for the
	// whole request before CorrelationID/RequestLogging/the actual handler
	// ever run, so their ctx already carries an active span
	// (infrastructure/logging.CorrelationHandler reads it back out as
	// trace_id — observability.md).
	return otelhttp.NewHandler(instrumented, "account-service"), healthHandler
}
