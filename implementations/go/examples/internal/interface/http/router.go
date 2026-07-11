package http

import (
	"net/http"

	"golang.org/x/time/rate"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/interface/http/middleware"
)

// NewRouter는 요청 핸들러를 조립한다. 반환하는 *HealthHandler는 main()이 SIGTERM 수신 시
// StartShutdown()을 호출해 readiness를 먼저 실패로 전환하는 데 쓴다(graceful-shutdown.md).
// limiter는 호출자가 조립한다(main()은 config.LoadRateLimitConfig()로, 테스트는 임계값이
// 훨씬 높은 limiter로) — rate-limiting.md의 "환경 변수로 임계값을 관리한다" 원칙에 따라
// 운영값과 테스트값을 분리하기 위해서다.
func NewRouter(repo account.Repository, outboxRelay command.OutboxRelay, jwtService *auth.JWTService, limiter *rate.Limiter) (http.Handler, *HealthHandler) {
	createAccountHandler := command.NewCreateAccountHandler(repo, outboxRelay)
	depositHandler := command.NewDepositHandler(repo, outboxRelay)
	withdrawHandler := command.NewWithdrawHandler(repo, outboxRelay)
	suspendAccountHandler := command.NewSuspendAccountHandler(repo, outboxRelay)
	reactivateAccountHandler := command.NewReactivateAccountHandler(repo, outboxRelay)
	closeAccountHandler := command.NewCloseAccountHandler(repo, outboxRelay)
	getAccountHandler := query.NewGetAccountHandler(repo)
	getTransactionsHandler := query.NewGetTransactionsHandler(repo)

	accountHTTP := NewAccountHandler(
		createAccountHandler,
		depositHandler,
		withdrawHandler,
		suspendAccountHandler,
		reactivateAccountHandler,
		closeAccountHandler,
		getAccountHandler,
		getTransactionsHandler,
	)
	authHTTP := NewAuthHandler(jwtService)
	healthHandler := NewHealthHandler()

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	protected.HandleFunc("POST /accounts/{id}/deposit", accountHTTP.Deposit)
	protected.HandleFunc("POST /accounts/{id}/withdraw", accountHTTP.Withdraw)
	protected.HandleFunc("POST /accounts/{id}/suspend", accountHTTP.SuspendAccount)
	protected.HandleFunc("POST /accounts/{id}/reactivate", accountHTTP.ReactivateAccount)
	protected.HandleFunc("POST /accounts/{id}/close", accountHTTP.CloseAccount)
	protected.HandleFunc("GET /accounts/{id}", accountHTTP.GetAccount)
	protected.HandleFunc("GET /accounts/{id}/transactions", accountHTTP.GetTransactions)

	// rate limit 대상 라우트
	limited := http.NewServeMux()
	limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)

	mux := http.NewServeMux()
	mux.Handle("/", middleware.RateLimit(limiter)(limited))
	// 헬스체크는 오케스트레이터 프로브 전용이므로 rate limit 미들웨어를 감싸지 않는다.
	mux.HandleFunc("GET /health/live", healthHandler.Live)
	mux.HandleFunc("GET /health/ready", healthHandler.Ready)

	return middleware.CorrelationID(mux), healthHandler
}
