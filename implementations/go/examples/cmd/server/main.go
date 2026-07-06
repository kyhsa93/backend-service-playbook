package main

import (
	"database/sql"
	"log"
	"net/http"
	"os"

	_ "github.com/lib/pq"

	"github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
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
	notifier := notification.NewService(notification.NewSESClient(), db)

	outboxWriter := outbox.NewWriter()
	outboxRelay := outbox.NewRelay(db, map[string]outbox.Handler{
		"AccountCreated":     event.NewAccountCreatedEventHandler(notifier).Handle,
		"MoneyDeposited":     event.NewMoneyDepositedEventHandler(notifier).Handle,
		"MoneyWithdrawn":     event.NewMoneyWithdrawnEventHandler(notifier).Handle,
		"AccountSuspended":   event.NewAccountSuspendedEventHandler(notifier).Handle,
		"AccountReactivated": event.NewAccountReactivatedEventHandler(notifier).Handle,
		"AccountClosed":      event.NewAccountClosedEventHandler(notifier).Handle,
	})

	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	mux := httphandler.NewRouter(accountRepo, outboxRelay)

	addr := ":8080"
	log.Printf("listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
