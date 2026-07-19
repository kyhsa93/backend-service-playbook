package http

import (
	"net/http"

	"golang.org/x/time/rate"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/domain/card"
	"github.com/example/account-service/internal/domain/credential"
	"github.com/example/account-service/internal/domain/payment"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/interface/http/middleware"
)

// PaymentStore는 Payment BC의 핸들러들이 나눠 필요로 하는 네 포트(Payment
// Repository/Query, Refund Repository/Query)를 하나로 묶는다 — 같은 concrete
// *persistence.PaymentRepository 값이 구조적으로 모두 만족하므로(account/card
// Repository와 동일한 관용구), 합성 루트(main)는 이 인터페이스 하나만 만족하는 값을
// 넘기면 된다.
type PaymentStore interface {
	payment.Repository
	payment.RefundRepository
}

// NewRouter는 요청 핸들러를 조립한다. 반환하는 *HealthHandler는 main()이 SIGTERM 수신 시
// StartShutdown()을 호출해 readiness를 먼저 실패로 전환하는 데 쓴다(graceful-shutdown.md).
// limiter는 호출자가 조립한다(main()은 config.LoadRateLimitConfig()로, 테스트는 임계값이
// 훨씬 높은 limiter로) — rate-limiting.md의 "환경 변수로 임계값을 관리한다" 원칙에 따라
// 운영값과 테스트값을 분리하기 위해서다.
func NewRouter(repo account.Repository, cardRepo card.Repository, credentialRepo credential.Repository, paymentStore PaymentStore, accountAdapter command.AccountAdapter, paymentCardAdapter command.PaymentCardAdapter, paymentAccountAdapter command.PaymentAccountAdapter, jwtService *auth.JWTService, passwordHasher command.PasswordHasher, limiter *rate.Limiter) (http.Handler, *HealthHandler) {
	createAccountHandler := command.NewCreateAccountHandler(repo)
	depositHandler := command.NewDepositHandler(repo)
	withdrawHandler := command.NewWithdrawHandler(repo)
	suspendAccountHandler := command.NewSuspendAccountHandler(repo)
	reactivateAccountHandler := command.NewReactivateAccountHandler(repo)
	closeAccountHandler := command.NewCloseAccountHandler(repo)
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
	// Card BC — 발급 시 accountAdapter(ACL)로 계좌 활성 여부를 동기 확인한다(cross-domain.md).
	issueCardHandler := command.NewIssueCardHandler(cardRepo, accountAdapter)
	getCardHandler := query.NewGetCardHandler(cardRepo)
	cardHTTP := NewCardHandler(issueCardHandler, getCardHandler)

	// Payment BC — 결제 시 paymentCardAdapter/paymentAccountAdapter(ACL)로 카드 활성 여부·
	// 계좌 활성 여부·잔액 충분 여부를 동기 확인한다. 실제 계좌 차감/보상 크레딧은 여기서
	// 하지 않는다 — payment.completed.v1/payment.cancelled.v1/refund.approved.v1
	// Integration Event를 Account BC가 비동기로 구독해 수행한다(cross-domain.md).
	createPaymentHandler := command.NewCreatePaymentHandler(paymentStore, paymentCardAdapter, paymentAccountAdapter)
	cancelPaymentHandler := command.NewCancelPaymentHandler(paymentStore)
	requestRefundHandler := command.NewRequestRefundHandler(paymentStore, paymentStore)
	getPaymentHandler := query.NewGetPaymentHandler(paymentStore)
	getPaymentsHandler := query.NewGetPaymentsHandler(paymentStore)
	getRefundsHandler := query.NewGetRefundsHandler(paymentStore, paymentStore)
	paymentHTTP := NewPaymentHandler(
		createPaymentHandler,
		cancelPaymentHandler,
		requestRefundHandler,
		getPaymentHandler,
		getPaymentsHandler,
		getRefundsHandler,
	)

	// Auth — jwtService는 이미 command.TokenIssuer(Sign(userID) (string, error))를
	// 구조적으로 만족하므로 그대로 SignInHandler에 주입한다(#188 — sign-in에 실제
	// 비밀번호 검증을 추가한 수정).
	signUpHandler := command.NewSignUpHandler(credentialRepo, passwordHasher)
	signInHandler := command.NewSignInHandler(credentialRepo, passwordHasher, jwtService)
	authHTTP := NewAuthHandler(signUpHandler, signInHandler)
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
	protected.HandleFunc("POST /cards", cardHTTP.IssueCard)
	protected.HandleFunc("GET /cards/{cardId}", cardHTTP.GetCard)
	protected.HandleFunc("POST /payments", paymentHTTP.CreatePayment)
	protected.HandleFunc("POST /payments/{paymentId}/cancel", paymentHTTP.CancelPayment)
	protected.HandleFunc("GET /payments/{paymentId}", paymentHTTP.GetPayment)
	protected.HandleFunc("GET /payments", paymentHTTP.GetPayments)
	protected.HandleFunc("POST /payments/{paymentId}/refunds", paymentHTTP.RequestRefund)
	protected.HandleFunc("GET /payments/{paymentId}/refunds", paymentHTTP.GetRefunds)

	// rate limit 대상 라우트
	limited := http.NewServeMux()
	limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/cards", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/cards/", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/payments", middleware.RequireAuth(jwtService)(protected))
	limited.Handle("/payments/", middleware.RequireAuth(jwtService)(protected))
	limited.HandleFunc("POST /auth/sign-up", authHTTP.SignUp)
	limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)

	mux := http.NewServeMux()
	mux.Handle("/", middleware.RateLimit(limiter)(limited))
	// 헬스체크는 오케스트레이터 프로브 전용이므로 rate limit 미들웨어를 감싸지 않는다.
	mux.HandleFunc("GET /health/live", healthHandler.Live)
	mux.HandleFunc("GET /health/ready", healthHandler.Ready)

	return middleware.CorrelationID(middleware.RequestLogging(mux)), healthHandler
}
