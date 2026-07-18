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

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/event"
	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/config"
	"github.com/example/account-service/internal/infrastructure/acl"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/infrastructure/logging"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
	"github.com/example/account-service/internal/infrastructure/persistence"
	"github.com/example/account-service/internal/infrastructure/secret"
	httphandler "github.com/example/account-service/internal/interface/http"
)

func main() {
	// CorrelationHandler가 JSONHandler를 감싸, ctx에 correlation ID가 있으면 모든
	// 로그 호출에 자동으로 "correlation_id" 필드를 추가한다 — 호출부마다 직접
	// 넘길 필요가 없다(observability.md).
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

	// 의존성 조립 — 프레임워크 없이 생성자 체이닝
	notifier := notification.NewService(notification.NewSESClient(), db)

	outboxWriter := outbox.NewWriter()
	outboxPublisher := outbox.NewPublisher(db)

	// Card BC 의존성. 합성 루트(main)만 Account와 Card 양쪽을 알고, 두 BC는 서로를
	// import하지 않는다 — Account는 Outbox에 Integration Event를 문자열 event_type으로
	// 적재하고, Card는 아래 handler map에서 그 문자열을 구독한다(cross-domain.md).
	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	cardRepo := persistence.NewCardRepository(db)
	credentialRepo := persistence.NewCredentialRepository(db)
	accountAdapter := acl.NewAccountAdapter(accountRepo)
	suspendCardsHandler := command.NewSuspendCardsByAccountHandler(cardRepo)
	cancelCardsHandler := command.NewCancelCardsByAccountHandler(cardRepo)

	// Payment BC 의존성 — Account/Card와 마찬가지로 합성 루트만 세 BC를 모두 알고, 서로는
	// Outbox의 문자열 event_type을 매개로만 연결된다. Payment는 Card/Account를 동기
	// Adapter(ACL)로 조회하고(paymentCardAdapter/paymentAccountAdapter), Account는 Payment의
	// Integration Event(payment.completed.v1 등)에 비동기로 반응한다(아래 handler map).
	paymentRepo := persistence.NewPaymentRepository(db)
	paymentCardAdapter := acl.NewPaymentCardAdapter(cardRepo)
	paymentAccountAdapter := acl.NewPaymentAccountAdapter(accountRepo)
	withdrawByPaymentHandler := command.NewWithdrawByPaymentHandler(accountRepo)
	depositByPaymentHandler := command.NewDepositByPaymentHandler(accountRepo)

	outboxRelay := outbox.NewRelay(db, map[string]outbox.Handler{
		"AccountCreated":     event.NewAccountCreatedEventHandler(notifier).Handle,
		"MoneyDeposited":     event.NewMoneyDepositedEventHandler(notifier).Handle,
		"MoneyWithdrawn":     event.NewMoneyWithdrawnEventHandler(notifier).Handle,
		"AccountSuspended":   event.NewAccountSuspendedEventHandler(notifier, outboxPublisher).Handle,
		"AccountReactivated": event.NewAccountReactivatedEventHandler(notifier).Handle,
		"AccountClosed":      event.NewAccountClosedEventHandler(notifier, outboxPublisher).Handle,
		// Payment BC의 Domain Event를 외부 BC용 Integration Event로 변환해 Outbox에
		// 다시 적재한다(account.AccountSuspended가 account.suspended.v1로 변환되는 것과
		// 동일한 관용구).
		"PaymentCompleted": event.NewPaymentCompletedEventHandler(outboxPublisher).Handle,
		"PaymentCancelled": event.NewPaymentCancelledEventHandler(outboxPublisher).Handle,
		"RefundApproved":   event.NewRefundApprovedEventHandler(outboxPublisher).Handle,
		// Card BC가 Account의 Integration Event에 반응한다(비동기). 언마샬 글루만 여기 두고
		// 실제 유스케이스는 Card Application 핸들러에 위임한다.
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
		// Account BC가 Payment의 Integration Event에 반응한다(비동기). payment.cancelled.v1과
		// refund.approved.v1은 둘 다 "이미 차감된 금액을 되돌린다"는 동일한 동작이라
		// DepositByPaymentHandler 하나를 재사용한다(referenceId만 paymentId/refundId로 다름).
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
	})

	secretService := secret.NewService(secret.NewSecretsManagerClient(), 5*time.Minute)
	jwtSecret, err := config.LoadJWTSecret(context.Background(), secretService, os.Getenv("APP_ENV"))
	if err != nil {
		slog.Error("failed to load jwt secret", "error", err)
		os.Exit(1)
	}
	jwtService := auth.NewJWTService(jwtSecret, time.Hour)
	passwordHasher := auth.NewBcryptPasswordHasher()

	rateLimitConfig := config.LoadRateLimitConfig()
	limiter := rate.NewLimiter(rate.Limit(rateLimitConfig.RequestsPerSecond), rateLimitConfig.Burst)

	mux, healthHandler := httphandler.NewRouter(accountRepo, cardRepo, credentialRepo, paymentRepo, accountAdapter, paymentCardAdapter, paymentAccountAdapter, outboxRelay, jwtService, passwordHasher, limiter)

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

	// srv.Shutdown(ctx)보다 반드시 먼저 호출한다 — readiness가 503으로 바뀐 뒤에야
	// 오케스트레이터가 새 트래픽을 끊으므로, HTTP 서버가 실제로 멈추기 전에 readiness부터
	// 실패시켜야 무중단 전환이 된다(graceful-shutdown.md).
	healthHandler.StartShutdown()

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
