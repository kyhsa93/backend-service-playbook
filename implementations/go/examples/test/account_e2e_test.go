package test

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/ses"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
	_ "github.com/lib/pq"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/localstack"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
	"golang.org/x/time/rate"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/event"
	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/infrastructure/acl"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/infrastructure/database"
	"github.com/example/account-service/internal/infrastructure/llm"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
	"github.com/example/account-service/internal/infrastructure/persistence"
	"github.com/example/account-service/internal/infrastructure/scheduling"
	taskqueue "github.com/example/account-service/internal/infrastructure/task-queue"
	httphandler "github.com/example/account-service/internal/interface/http"
	taskinterface "github.com/example/account-service/internal/interface/task"
)

var (
	testServer     *httptest.Server
	testDB         *sql.DB
	localstackHTTP string // the LocalStack container's base URL (e.g. http://localhost:32771)
	testJWTService *auth.JWTService

	// Exposed so the scheduling e2e tests (scheduling_e2e_test.go) can call
	// the Cron handlers directly instead of waiting for a real tick (the
	// same test pattern as the scheduling.md example).
	testInterestScheduler  *scheduling.InterestScheduler
	testStatementScheduler *scheduling.StatementScheduler
)

const (
	ownerID      = "owner-1"
	otherOwnerID = "owner-2"
)

func TestMain(m *testing.M) {
	os.Exit(runTests(m))
}

func runTests(m *testing.M) int {
	ctx := context.Background()

	pgContainer, err := postgres.Run(ctx, "postgres:16-alpine",
		postgres.WithDatabase("account_test"),
		postgres.WithUsername("test"),
		postgres.WithPassword("test"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").WithOccurrence(2).WithStartupTimeout(60*time.Second),
		),
	)
	if err != nil {
		panic(fmt.Sprintf("failed to start postgres container: %v", err))
	}
	defer func() { _ = pgContainer.Terminate(ctx) }()

	connStr, err := pgContainer.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		panic(err)
	}

	db, err := sql.Open("postgres", connStr)
	if err != nil {
		panic(err)
	}
	defer func() { _ = db.Close() }()
	testDB = db

	if err := waitForDB(db, 30, time.Second); err != nil {
		panic(fmt.Sprintf("db did not become ready: %v", err))
	}

	for _, migration := range []string{"0001_init.sql", "0002_add_email_and_sent_emails.sql", "0003_add_outbox.sql", "0004_add_card.sql", "0005_add_credential.sql", "0006_add_payment.sql", "0007_add_scheduling.sql"} {
		schema, err := os.ReadFile(filepath.Join("..", "migrations", migration))
		if err != nil {
			panic(err)
		}
		if _, err := db.Exec(string(schema)); err != nil {
			panic(fmt.Sprintf("failed to apply migration %s: %v", migration, err))
		}
	}

	// Emulates SES/SQS with LocalStack. Must pin 3.0 (the free Community
	// tag) — :latest requires a paid license and the container exits
	// immediately.
	localstackContainer, err := localstack.Run(ctx, "localstack/localstack:3.0",
		testcontainers.WithEnv(map[string]string{"SERVICES": "ses,sqs"}),
	)
	if err != nil {
		panic(fmt.Sprintf("failed to start localstack container: %v", err))
	}
	defer func() { _ = testcontainers.TerminateContainer(localstackContainer) }()

	host, err := localstackContainer.Host(ctx)
	if err != nil {
		panic(err)
	}
	port, err := localstackContainer.MappedPort(ctx, "4566/tcp")
	if err != nil {
		panic(err)
	}
	localstackHTTP = fmt.Sprintf("http://%s:%s", host, port.Port())

	// Builds the SES client via the same path as production (env vars →
	// notification.NewSESClient()).
	_ = os.Setenv("AWS_ENDPOINT_URL", localstackHTTP)
	_ = os.Setenv("AWS_REGION", "us-east-1")
	_ = os.Setenv("AWS_ACCESS_KEY_ID", "test")
	_ = os.Setenv("AWS_SECRET_ACCESS_KEY", "test")
	sesClient := notification.NewSESClient()

	// Just like real SES, LocalStack's SES emulator also requires sender
	// verification — without it, it fails with MessageRejected: Email
	// address not verified.
	if _, err := sesClient.VerifyEmailIdentity(ctx, &ses.VerifyEmailIdentityInput{
		EmailAddress: aws.String(notification.SenderEmail()),
	}); err != nil {
		panic(fmt.Sprintf("failed to verify sender identity: %v", err))
	}

	notifier := notification.NewService(sesClient, db)

	// Builds the SQS client via the same path as production (env vars →
	// outbox.NewSQSClient()).
	sqsClient := outbox.NewSQSClient()
	queueURL := setupDomainEventQueue(ctx, sqsClient)

	outboxWriter := outbox.NewWriter()
	outboxPublisher := outbox.NewPublisher(db)

	repo := persistence.NewAccountRepository(db, outboxWriter)
	cardRepo := persistence.NewCardRepository(db)
	credentialRepo := persistence.NewCredentialRepository(db)
	paymentRepo := persistence.NewPaymentRepository(db)
	accountAdapter := acl.NewAccountAdapter(repo)
	paymentCardAdapter := acl.NewPaymentCardAdapter(cardRepo)
	paymentAccountAdapter := acl.NewPaymentAccountAdapter(repo)
	suspendCardsHandler := command.NewSuspendCardsByAccountHandler(cardRepo)
	cancelCardsHandler := command.NewCancelCardsByAccountHandler(cardRepo)
	withdrawByPaymentHandler := command.NewWithdrawByPaymentHandler(repo)
	depositByPaymentHandler := command.NewDepositByPaymentHandler(repo)

	outboxHandlers := map[string]outbox.Handler{
		"AccountCreated":     event.NewAccountCreatedEventHandler(notifier).Handle,
		"MoneyDeposited":     event.NewMoneyDepositedEventHandler(notifier).Handle,
		"MoneyWithdrawn":     event.NewMoneyWithdrawnEventHandler(notifier).Handle,
		"AccountSuspended":   event.NewAccountSuspendedEventHandler(notifier, outboxPublisher).Handle,
		"AccountReactivated": event.NewAccountReactivatedEventHandler(notifier).Handle,
		"AccountClosed":      event.NewAccountClosedEventHandler(notifier, outboxPublisher).Handle,
		"PaymentCompleted":   event.NewPaymentCompletedEventHandler(outboxPublisher).Handle,
		"PaymentCancelled":   event.NewPaymentCancelledEventHandler(outboxPublisher).Handle,
		"RefundApproved":     event.NewRefundApprovedEventHandler(outboxPublisher).Handle,
		"InterestPaid":       event.NewInterestPaidEventHandler(notifier).Handle,
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
	}

	// Launches the Poller (Outbox → SQS publish) and Consumer (SQS → Handler
	// execution) as independent goroutines, just like main() — Command
	// Handlers never reference either of them at all (no synchronous
	// draining, domain-events.md). This ctx is reused as-is so both stop
	// together when runTests (the entire test process) ends.
	go outbox.NewPoller(db, sqsClient, queueURL).Run(ctx)
	go outbox.NewConsumer(sqsClient, queueURL, outboxHandlers).Run(ctx)

	// Task Queue infrastructure — reproduces the same assembly as main.go in
	// tests too (a separate SQS queue conceptually distinct from the
	// domain-events queue, per the "Task Queue vs Domain Event" distinction
	// in docs/architecture/domain-events.md).
	taskQueueURL := setupTaskQueue(ctx, sqsClient)
	taskWriter := taskqueue.NewWriter(db)
	testInterestScheduler = scheduling.NewInterestScheduler(taskWriter)
	testStatementScheduler = scheduling.NewStatementScheduler(taskWriter)

	applyInterestHandler := command.NewApplyDailyInterestHandler(repo, 0.0001)
	cardPaymentAdapter := acl.NewCardPaymentAdapter(paymentRepo)
	sendStatementHandler := command.NewSendCardUsageStatementHandler(cardRepo, accountAdapter, cardPaymentAdapter, notifier)
	interestTaskController := taskinterface.NewInterestTaskController(applyInterestHandler)
	statementTaskController := taskinterface.NewStatementTaskController(sendStatementHandler)

	taskHandlers := map[string]taskqueue.Handler{
		"account.apply-interest":    interestTaskController.HandleApplyInterest,
		"card.send-usage-statement": statementTaskController.HandleSendStatement,
	}
	go taskqueue.NewPoller(db, sqsClient, taskQueueURL).Run(ctx)
	go taskqueue.NewConsumer(sqsClient, taskQueueURL, taskHandlers).Run(ctx)

	testJWTService = auth.NewJWTService("test-secret", time.Hour)
	testPasswordHasher := auth.NewBcryptPasswordHasher()

	// A placeholder, unreachable base URL, same idiom as "test-secret" above — pointing at no
	// real Ollama instance makes the HTTP call fail, and RefundReasonClassifierImpl falls back
	// to a neutral classification on any failure (see its own doc comment), so refund e2e
	// assertions never depend on a live LLM call.
	testRefundReasonClassifier := llm.NewRefundReasonClassifierImpl("http://localhost:1", "qwen2.5:1.5b")

	// e2e tests send dozens of requests in a short time within the same
	// process — using the production default (100/second, burst 20) as-is
	// would let the rate limiter return 429 mid-test, causing unrelated
	// failures. Following rate-limiting.md's "manage thresholds via
	// environment variables" principle, override it with a generous limiter
	// here only.
	testLimiter := rate.NewLimiter(rate.Limit(100_000), 100_000)

	mux, _ := httphandler.NewRouter(repo, cardRepo, credentialRepo, paymentRepo, accountAdapter, paymentCardAdapter, paymentAccountAdapter, testJWTService, testPasswordHasher, testRefundReasonClassifier, testLimiter, database.NewManager(db))
	testServer = httptest.NewServer(mux)
	defer testServer.Close()

	return m.Run()
}

// setupDomainEventQueue creates the domain-events/domain-events-dlq queues
// with the same parameters as nestjs's localstack/init-sqs.sh (the same
// queue names, RedrivePolicy maxReceiveCount=3) and returns the main queue
// URL. init-sqs.sh plays this role for docker-compose-based local runs, but
// the LocalStack instance testcontainers-go spins up fresh for every test
// has no such init script mounted, so the SQS API is called directly here to
// produce the same state.
func setupDomainEventQueue(ctx context.Context, sqsClient *sqs.Client) string {
	dlqOut, err := sqsClient.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String("domain-events-dlq")})
	if err != nil {
		panic(fmt.Sprintf("failed to create domain-events-dlq: %v", err))
	}

	dlqAttrs, err := sqsClient.GetQueueAttributes(ctx, &sqs.GetQueueAttributesInput{
		QueueUrl:       dlqOut.QueueUrl,
		AttributeNames: []types.QueueAttributeName{types.QueueAttributeNameQueueArn},
	})
	if err != nil {
		panic(fmt.Sprintf("failed to read domain-events-dlq ARN: %v", err))
	}
	dlqArn := dlqAttrs.Attributes[string(types.QueueAttributeNameQueueArn)]

	redrivePolicy := fmt.Sprintf(`{"deadLetterTargetArn":"%s","maxReceiveCount":"3"}`, dlqArn)
	queueOut, err := sqsClient.CreateQueue(ctx, &sqs.CreateQueueInput{
		QueueName:  aws.String("domain-events"),
		Attributes: map[string]string{string(types.QueueAttributeNameRedrivePolicy): redrivePolicy},
	})
	if err != nil {
		panic(fmt.Sprintf("failed to create domain-events queue: %v", err))
	}
	return *queueOut.QueueUrl
}

// setupTaskQueue, for the same reason as setupDomainEventQueue, directly
// reproduces the task-queue/task-queue-dlq that localstack/init-sqs.sh
// creates on the testcontainers-go LocalStack too.
func setupTaskQueue(ctx context.Context, sqsClient *sqs.Client) string {
	dlqOut, err := sqsClient.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String("task-queue-dlq")})
	if err != nil {
		panic(fmt.Sprintf("failed to create task-queue-dlq: %v", err))
	}

	dlqAttrs, err := sqsClient.GetQueueAttributes(ctx, &sqs.GetQueueAttributesInput{
		QueueUrl:       dlqOut.QueueUrl,
		AttributeNames: []types.QueueAttributeName{types.QueueAttributeNameQueueArn},
	})
	if err != nil {
		panic(fmt.Sprintf("failed to read task-queue-dlq ARN: %v", err))
	}
	dlqArn := dlqAttrs.Attributes[string(types.QueueAttributeNameQueueArn)]

	redrivePolicy := fmt.Sprintf(`{"deadLetterTargetArn":"%s","maxReceiveCount":"3"}`, dlqArn)
	queueOut, err := sqsClient.CreateQueue(ctx, &sqs.CreateQueueInput{
		QueueName:  aws.String("task-queue"),
		Attributes: map[string]string{string(types.QueueAttributeNameRedrivePolicy): redrivePolicy},
	})
	if err != nil {
		panic(fmt.Sprintf("failed to create task-queue: %v", err))
	}
	return *queueOut.QueueUrl
}

func waitForDB(db *sql.DB, attempts int, interval time.Duration) error {
	var err error
	for i := 0; i < attempts; i++ {
		if err = db.Ping(); err == nil {
			return nil
		}
		time.Sleep(interval)
	}
	return err
}

func doRequest(t *testing.T, method, path, userID string, body any) *http.Response {
	t.Helper()

	var reqBody *bytes.Reader
	if body != nil {
		b, err := json.Marshal(body)
		require.NoError(t, err)
		reqBody = bytes.NewReader(b)
	} else {
		reqBody = bytes.NewReader(nil)
	}

	req, err := http.NewRequest(method, testServer.URL+path, reqBody)
	require.NoError(t, err)
	if userID != "" {
		token, err := testJWTService.Sign(userID)
		require.NoError(t, err)
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := testServer.Client().Do(req)
	require.NoError(t, err)
	return resp
}

func decodeBody(t *testing.T, resp *http.Response) map[string]any {
	t.Helper()
	defer func() { _ = resp.Body.Close() }()
	var result map[string]any
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&result))
	return result
}

func createAccount(t *testing.T, ownerID, currency string) map[string]any {
	t.Helper()
	return createAccountWithEmail(t, ownerID, ownerID+"@example.com", currency)
}

func createAccountWithEmail(t *testing.T, ownerID, email, currency string) map[string]any {
	t.Helper()
	resp := doRequest(t, http.MethodPost, "/accounts", ownerID,
		map[string]string{"email": email, "currency": currency})
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	return decodeBody(t, resp)
}

func TestAuth(t *testing.T) {
	t.Run("can_access_a_protected_endpoint_with_a_token_issued_by_sign_in", func(t *testing.T) {
		signUpResp := doRequest(t, http.MethodPost, "/auth/sign-up", "", map[string]string{"userId": ownerID, "password": "password123!"})
		require.Equal(t, http.StatusCreated, signUpResp.StatusCode)

		resp := doRequest(t, http.MethodPost, "/auth/sign-in", "", map[string]string{"userId": ownerID, "password": "password123!"})
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.NotEmpty(t, body["accessToken"])

		req, err := http.NewRequest(http.MethodPost, testServer.URL+"/accounts", bytes.NewReader(
			[]byte(`{"email":"`+ownerID+`@example.com","currency":"KRW"}`)))
		require.NoError(t, err)
		req.Header.Set("Authorization", "Bearer "+body["accessToken"].(string))
		req.Header.Set("Content-Type", "application/json")
		created, err := testServer.Client().Do(req)
		require.NoError(t, err)
		require.Equal(t, http.StatusCreated, created.StatusCode)
	})

	t.Run("missing_Authorization_header_returns_401", func(t *testing.T) {
		req, err := http.NewRequest(http.MethodPost, testServer.URL+"/accounts", bytes.NewReader(
			[]byte(`{"email":"no-auth@example.com","currency":"KRW"}`)))
		require.NoError(t, err)
		req.Header.Set("Content-Type", "application/json")
		resp, err := testServer.Client().Do(req)
		require.NoError(t, err)
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
	})

	t.Run("invalid_token_returns_401", func(t *testing.T) {
		req, err := http.NewRequest(http.MethodPost, testServer.URL+"/accounts", bytes.NewReader(
			[]byte(`{"email":"bad-token@example.com","currency":"KRW"}`)))
		require.NoError(t, err)
		req.Header.Set("Authorization", "Bearer not-a-real-token")
		req.Header.Set("Content-Type", "application/json")
		resp, err := testServer.Client().Do(req)
		require.NoError(t, err)
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
	})
}

func TestCreateAccount(t *testing.T) {
	t.Run("valid_create_request_returns_201_and_account_info", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts", ownerID,
			map[string]string{"email": ownerID + "@example.com", "currency": "KRW"})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, ownerID, body["ownerId"])
		require.Equal(t, ownerID+"@example.com", body["email"])
		require.Equal(t, "ACTIVE", body["status"])
		require.NotEmpty(t, body["accountId"])
		require.NotEmpty(t, body["createdAt"])
		balance := body["balance"].(map[string]any)
		require.Equal(t, float64(0), balance["amount"])
		require.Equal(t, "KRW", balance["currency"])
	})

	t.Run("empty_email_returns_400", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts", ownerID, map[string]string{"currency": "KRW"})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("invalid_email_format_returns_400", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts", ownerID,
			map[string]string{"email": "not-an-email", "currency": "KRW"})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestDeposit(t *testing.T) {
	t.Run("valid_deposit_request_returns_201_and_transaction_history", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, account["accountId"], body["accountId"])
		require.Equal(t, "DEPOSIT", body["type"])
		require.NotEmpty(t, body["transactionId"])
	})

	t.Run("nonexistent_account_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/deposit", ownerID, map[string]int{"amount": 10000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("another_owners_account_returns_404", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", otherOwnerID,
			map[string]int{"amount": 10000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("amount_of_zero_or_less_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 0})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("suspended_account_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestWithdraw(t *testing.T) {
	t.Run("valid_withdraw_request_returns_201_and_transaction_history", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": 4000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, "WITHDRAWAL", body["type"])
	})

	t.Run("nonexistent_account_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/withdraw", ownerID, map[string]int{"amount": 1000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("withdrawing_more_than_the_balance_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("suspended_account_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("amount_of_zero_or_less_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": -1})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestTransfer(t *testing.T) {
	t.Run("valid_transfer_request_returns_201_and_withdraw_deposit_transaction_history", func(t *testing.T) {
		source := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		target := createAccount(t, otherOwnerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": target["accountId"], "amount": 4000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.NotEmpty(t, body["transferId"])
		sourceTx := body["sourceTransaction"].(map[string]any)
		targetTx := body["targetTransaction"].(map[string]any)
		require.Equal(t, "WITHDRAWAL", sourceTx["type"])
		require.Equal(t, "DEPOSIT", targetTx["type"])

		sourceGet := decodeBody(t, doRequest(t, http.MethodGet, "/accounts/"+source["accountId"].(string), ownerID, nil))
		require.InDelta(t, float64(6000), sourceGet["balance"].(map[string]any)["amount"], 0)

		targetGet := decodeBody(t, doRequest(t, http.MethodGet, "/accounts/"+target["accountId"].(string), otherOwnerID, nil))
		require.InDelta(t, float64(4000), targetGet["balance"].(map[string]any)["amount"], 0)
	})

	t.Run("can_also_transfer_to_another_owners_account", func(t *testing.T) {
		source := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		target := createAccount(t, otherOwnerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": target["accountId"], "amount": 1000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)
	})

	t.Run("nonexistent_source_account_returns_404", func(t *testing.T) {
		target := createAccount(t, otherOwnerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/transfer", ownerID,
			map[string]any{"targetAccountId": target["accountId"], "amount": 1000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("nonexistent_target_account_returns_404", func(t *testing.T) {
		source := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})

		resp := doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": "non-existent", "amount": 1000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("same_source_and_target_account_returns_400", func(t *testing.T) {
		acc := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+acc["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})

		resp := doRequest(t, http.MethodPost, "/accounts/"+acc["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": acc["accountId"], "amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, "ACCOUNT_TRANSFER_SAME_ACCOUNT", body["code"])
	})

	t.Run("transferring_more_than_the_balance_returns_400", func(t *testing.T) {
		source := createAccount(t, ownerID, "KRW")
		target := createAccount(t, otherOwnerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": target["accountId"], "amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, "ACCOUNT_INSUFFICIENT_BALANCE", body["code"])
	})

	t.Run("suspended_source_account_returns_400", func(t *testing.T) {
		source := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/suspend", ownerID, nil)
		target := createAccount(t, otherOwnerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": target["accountId"], "amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, "ACCOUNT_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT", body["code"])
	})

	t.Run("suspended_target_account_returns_400", func(t *testing.T) {
		source := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		target := createAccount(t, otherOwnerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+target["accountId"].(string)+"/suspend", otherOwnerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": target["accountId"], "amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, "ACCOUNT_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT", body["code"])
	})

	t.Run("mismatched_currency_returns_400", func(t *testing.T) {
		source := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		target := createAccount(t, otherOwnerID, "USD")

		resp := doRequest(t, http.MethodPost, "/accounts/"+source["accountId"].(string)+"/transfer", ownerID,
			map[string]any{"targetAccountId": target["accountId"], "amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, "ACCOUNT_CURRENCY_MISMATCH", body["code"])
	})
}

func TestSuspendAccount(t *testing.T) {
	t.Run("suspending_a_normal_account_returns_204", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		getResp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		getBody := decodeBody(t, getResp)
		require.Equal(t, "SUSPENDED", getBody["status"])
	})

	t.Run("nonexistent_account_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/suspend", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("already_suspended_account_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestReactivateAccount(t *testing.T) {
	t.Run("resuming_a_suspended_account_returns_204", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/reactivate", ownerID, nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		getResp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		getBody := decodeBody(t, getResp)
		require.Equal(t, "ACTIVE", getBody["status"])
	})

	t.Run("nonexistent_account_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/reactivate", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("resuming_an_active_account_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/reactivate", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestCloseAccount(t *testing.T) {
	t.Run("closing_an_account_with_zero_balance_returns_204", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		getResp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		getBody := decodeBody(t, getResp)
		require.Equal(t, "CLOSED", getBody["status"])
	})

	t.Run("nonexistent_account_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/close", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("nonzero_balance_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 5000})

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("already_closed_account_returns_400", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestGetAccount(t *testing.T) {
	t.Run("looking_up_an_existing_account_returns_200_and_account_info", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		require.Equal(t, http.StatusOK, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, account["accountId"], body["accountId"])
		require.Equal(t, ownerID, body["ownerId"])
		require.NotEmpty(t, body["updatedAt"])
	})

	t.Run("nonexistent_account_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodGet, "/accounts/non-existent", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("lookup_by_another_owner_returns_404", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), otherOwnerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})
}

func TestGetTransactions(t *testing.T) {
	t.Run("returns_transaction_history_with_pagination", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": 3000})

		resp := doRequest(t, http.MethodGet,
			"/accounts/"+account["accountId"].(string)+"/transactions?page=0&take=20", ownerID, nil)
		require.Equal(t, http.StatusOK, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, float64(2), body["count"])
		transactions := body["transactions"].([]any)
		require.Len(t, transactions, 2)
	})

	t.Run("nonexistent_account_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodGet, "/accounts/non-existent/transactions", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("a_page_lookup_beyond_take_returns_an_empty_array", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodGet,
			"/accounts/"+account["accountId"].(string)+"/transactions?page=5&take=20", ownerID, nil)
		require.Equal(t, http.StatusOK, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, float64(0), body["count"])
		transactions := body["transactions"]
		if transactions != nil {
			require.Len(t, transactions.([]any), 0)
		}
	})
}
