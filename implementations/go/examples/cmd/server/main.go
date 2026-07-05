package main

import (
	"database/sql"
	"log"
	"net/http"
	"os"

	_ "github.com/lib/pq"

	appcmd "github.com/example/account-service/internal/application/command"
	appqry "github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/infrastructure/persistence"
	httphandler "github.com/example/account-service/internal/interface/http"
)

func main() {
	db, err := sql.Open("postgres", os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
	}
	defer db.Close()

	// 의존성 조립 — 프레임워크 없이 생성자 체이닝
	accountRepo := persistence.NewAccountRepository(db)

	createAccountHandler := appcmd.NewCreateAccountHandler(accountRepo)
	depositHandler := appcmd.NewDepositHandler(accountRepo)
	withdrawHandler := appcmd.NewWithdrawHandler(accountRepo)
	suspendAccountHandler := appcmd.NewSuspendAccountHandler(accountRepo)
	reactivateAccountHandler := appcmd.NewReactivateAccountHandler(accountRepo)
	closeAccountHandler := appcmd.NewCloseAccountHandler(accountRepo)
	getAccountHandler := appqry.NewGetAccountHandler(accountRepo)
	getTransactionsHandler := appqry.NewGetTransactionsHandler(accountRepo)

	accountHTTP := httphandler.NewAccountHandler(
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

	addr := ":8080"
	log.Printf("listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
