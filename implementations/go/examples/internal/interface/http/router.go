package http

import (
	"net/http"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/interface/http/middleware"
)

func NewRouter(repo account.Repository, outboxRelay command.OutboxRelay, jwtService *auth.JWTService) *http.ServeMux {
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

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	protected.HandleFunc("POST /accounts/{id}/deposit", accountHTTP.Deposit)
	protected.HandleFunc("POST /accounts/{id}/withdraw", accountHTTP.Withdraw)
	protected.HandleFunc("POST /accounts/{id}/suspend", accountHTTP.SuspendAccount)
	protected.HandleFunc("POST /accounts/{id}/reactivate", accountHTTP.ReactivateAccount)
	protected.HandleFunc("POST /accounts/{id}/close", accountHTTP.CloseAccount)
	protected.HandleFunc("GET /accounts/{id}", accountHTTP.GetAccount)
	protected.HandleFunc("GET /accounts/{id}/transactions", accountHTTP.GetTransactions)

	mux := http.NewServeMux()
	mux.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
	mux.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	mux.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)
	return mux
}
