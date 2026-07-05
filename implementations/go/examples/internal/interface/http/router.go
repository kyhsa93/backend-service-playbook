package http

import (
	"net/http"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
)

func NewRouter(repo account.Repository) *http.ServeMux {
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

	mux := http.NewServeMux()
	mux.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	mux.HandleFunc("POST /accounts/{id}/deposit", accountHTTP.Deposit)
	mux.HandleFunc("POST /accounts/{id}/withdraw", accountHTTP.Withdraw)
	mux.HandleFunc("POST /accounts/{id}/suspend", accountHTTP.SuspendAccount)
	mux.HandleFunc("POST /accounts/{id}/reactivate", accountHTTP.ReactivateAccount)
	mux.HandleFunc("POST /accounts/{id}/close", accountHTTP.CloseAccount)
	mux.HandleFunc("GET /accounts/{id}", accountHTTP.GetAccount)
	mux.HandleFunc("GET /accounts/{id}/transactions", accountHTTP.GetTransactions)
	return mux
}
