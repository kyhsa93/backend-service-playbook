package main

import (
	"database/sql"
	"log"
	"net/http"
	"os"

	_ "github.com/lib/pq"

	appcmd "github.com/example/order-service/internal/application/command"
	appqry "github.com/example/order-service/internal/application/query"
	"github.com/example/order-service/internal/infrastructure/persistence"
	httphandler "github.com/example/order-service/internal/interface/http"
)

func main() {
	db, err := sql.Open("postgres", os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
	}
	defer db.Close()

	// 의존성 조립 — 프레임워크 없이 생성자 체이닝
	orderRepo := persistence.NewOrderRepository(db)

	createHandler := appcmd.NewCreateOrderHandler(orderRepo)
	cancelHandler := appcmd.NewCancelOrderHandler(orderRepo)
	getHandler    := appqry.NewGetOrderHandler(orderRepo)
	getallHandler := appqry.NewGetOrdersHandler(orderRepo)

	orderHTTP := httphandler.NewOrderHandler(createHandler, cancelHandler, getHandler, getallHandler)

	mux := http.NewServeMux()
	mux.HandleFunc("POST /orders", orderHTTP.CreateOrder)
	mux.HandleFunc("GET /orders/{id}", orderHTTP.GetOrder)
	mux.HandleFunc("POST /orders/{id}/cancel", orderHTTP.CancelOrder)

	addr := ":8080"
	log.Printf("listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
