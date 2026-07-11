package main

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/lib/pq"

	"github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/config"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
	"github.com/example/account-service/internal/infrastructure/persistence"
	"github.com/example/account-service/internal/infrastructure/secret"
	httphandler "github.com/example/account-service/internal/interface/http"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))

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

	secretService := secret.NewService(secret.NewSecretsManagerClient(), 5*time.Minute)
	jwtSecret, err := config.LoadJWTSecret(context.Background(), secretService, os.Getenv("APP_ENV"))
	if err != nil {
		slog.Error("failed to load jwt secret", "error", err)
		os.Exit(1)
	}
	jwtService := auth.NewJWTService(jwtSecret, time.Hour)

	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	mux := httphandler.NewRouter(accountRepo, outboxRelay, jwtService)

	srv := &http.Server{Addr: ":8080", Handler: mux}

	// SIGTERM/SIGINT를 받으면 ctx가 취소된다.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		slog.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server error", "error", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done() // SIGTERM/SIGINT 수신까지 블록
	slog.Info("shutdown signal received")

	// terminationGracePeriodSeconds(오케스트레이터 설정)에 맞춰 여유 있게 설정.
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// 진행 중인 요청이 끝날 때까지 대기하며 새 연결은 거부한다.
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown failed", "error", err)
	}
	slog.Info("server stopped")
	// defer db.Close()가 이 시점 이후에 실행됨 — HTTP 서버가 완전히 닫힌 뒤 DB 연결 정리
}
