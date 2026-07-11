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
	_ "github.com/lib/pq"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/localstack"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
	"golang.org/x/time/rate"

	"github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
	"github.com/example/account-service/internal/infrastructure/persistence"
	httphandler "github.com/example/account-service/internal/interface/http"
)

var (
	testServer     *httptest.Server
	testDB         *sql.DB
	localstackHTTP string // LocalStack 컨테이너의 base URL (예: http://localhost:32771)
	testJWTService *auth.JWTService
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
	defer pgContainer.Terminate(ctx)

	connStr, err := pgContainer.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		panic(err)
	}

	db, err := sql.Open("postgres", connStr)
	if err != nil {
		panic(err)
	}
	defer db.Close()
	testDB = db

	if err := waitForDB(db, 30, time.Second); err != nil {
		panic(fmt.Sprintf("db did not become ready: %v", err))
	}

	for _, migration := range []string{"0001_init.sql", "0002_add_email_and_sent_emails.sql", "0003_add_outbox.sql"} {
		schema, err := os.ReadFile(filepath.Join("..", "migrations", migration))
		if err != nil {
			panic(err)
		}
		if _, err := db.Exec(string(schema)); err != nil {
			panic(fmt.Sprintf("failed to apply migration %s: %v", migration, err))
		}
	}

	// LocalStack로 SES를 에뮬레이션한다. 반드시 3.0(무료 Community 태그)을 고정한다 —
	// :latest는 유료 라이선스를 요구해 컨테이너가 즉시 종료된다.
	localstackContainer, err := localstack.Run(ctx, "localstack/localstack:3.0",
		testcontainers.WithEnv(map[string]string{"SERVICES": "ses"}),
	)
	if err != nil {
		panic(fmt.Sprintf("failed to start localstack container: %v", err))
	}
	defer testcontainers.TerminateContainer(localstackContainer)

	host, err := localstackContainer.Host(ctx)
	if err != nil {
		panic(err)
	}
	port, err := localstackContainer.MappedPort(ctx, "4566/tcp")
	if err != nil {
		panic(err)
	}
	localstackHTTP = fmt.Sprintf("http://%s:%s", host, port.Port())

	// 프로덕션과 동일한 경로(환경변수 → notification.NewSESClient())로 SES 클라이언트를 만든다.
	os.Setenv("AWS_ENDPOINT_URL", localstackHTTP)
	os.Setenv("AWS_REGION", "us-east-1")
	os.Setenv("AWS_ACCESS_KEY_ID", "test")
	os.Setenv("AWS_SECRET_ACCESS_KEY", "test")
	sesClient := notification.NewSESClient()

	// 실제 SES와 마찬가지로 LocalStack의 SES 에뮬레이터도 발신자 검증을 요구한다 —
	// 검증하지 않으면 MessageRejected: Email address not verified로 실패한다.
	if _, err := sesClient.VerifyEmailIdentity(ctx, &ses.VerifyEmailIdentityInput{
		EmailAddress: aws.String(notification.SenderEmail()),
	}); err != nil {
		panic(fmt.Sprintf("failed to verify sender identity: %v", err))
	}

	notifier := notification.NewService(sesClient, db)

	outboxWriter := outbox.NewWriter()
	outboxRelay := outbox.NewRelay(db, map[string]outbox.Handler{
		"AccountCreated":     event.NewAccountCreatedEventHandler(notifier).Handle,
		"MoneyDeposited":     event.NewMoneyDepositedEventHandler(notifier).Handle,
		"MoneyWithdrawn":     event.NewMoneyWithdrawnEventHandler(notifier).Handle,
		"AccountSuspended":   event.NewAccountSuspendedEventHandler(notifier).Handle,
		"AccountReactivated": event.NewAccountReactivatedEventHandler(notifier).Handle,
		"AccountClosed":      event.NewAccountClosedEventHandler(notifier).Handle,
	})

	testJWTService = auth.NewJWTService("test-secret", time.Hour)

	// e2e 테스트는 같은 프로세스 안에서 짧은 시간에 수십 개 요청을 보낸다 — 운영 기본값
	// (초당 100개, burst 20)을 그대로 쓰면 rate limiter가 테스트 도중에도 429를 반환해
	// 무관한 실패를 만든다. rate-limiting.md의 "환경 변수로 임계값을 관리한다" 원칙에 따라
	// 여기서만 넉넉한 limiter로 override한다.
	testLimiter := rate.NewLimiter(rate.Limit(100_000), 100_000)

	repo := persistence.NewAccountRepository(db, outboxWriter)
	mux, _ := httphandler.NewRouter(repo, outboxRelay, testJWTService, testLimiter)
	testServer = httptest.NewServer(mux)
	defer testServer.Close()

	return m.Run()
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
	defer resp.Body.Close()
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
	t.Run("sign-in으로_발급받은_토큰으로_보호된_엔드포인트에_접근할_수_있다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/auth/sign-in", "", map[string]string{"userId": ownerID})
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

	t.Run("Authorization_헤더가_없으면_401을_반환한다", func(t *testing.T) {
		req, err := http.NewRequest(http.MethodPost, testServer.URL+"/accounts", bytes.NewReader(
			[]byte(`{"email":"no-auth@example.com","currency":"KRW"}`)))
		require.NoError(t, err)
		req.Header.Set("Content-Type", "application/json")
		resp, err := testServer.Client().Do(req)
		require.NoError(t, err)
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
	})

	t.Run("유효하지_않은_토큰이면_401을_반환한다", func(t *testing.T) {
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
	t.Run("생성_요청이_유효하면_201과_계좌_정보를_반환한다", func(t *testing.T) {
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

	t.Run("email이_비어있으면_400을_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts", ownerID, map[string]string{"currency": "KRW"})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("email_형식이_유효하지_않으면_400을_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts", ownerID,
			map[string]string{"email": "not-an-email", "currency": "KRW"})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestDeposit(t *testing.T) {
	t.Run("입금_요청이_유효하면_201과_거래_내역을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, account["accountId"], body["accountId"])
		require.Equal(t, "DEPOSIT", body["type"])
		require.NotEmpty(t, body["transactionId"])
	})

	t.Run("존재하지_않는_계좌면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/deposit", ownerID, map[string]int{"amount": 10000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("다른_소유자의_계좌면_404를_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", otherOwnerID,
			map[string]int{"amount": 10000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("금액이_0_이하이면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 0})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("정지된_계좌면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestWithdraw(t *testing.T) {
	t.Run("출금_요청이_유효하면_201과_거래_내역을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 10000})

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": 4000})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, "WITHDRAWAL", body["type"])
	})

	t.Run("존재하지_않는_계좌면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/withdraw", ownerID, map[string]int{"amount": 1000})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("잔액보다_큰_금액을_출금하면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("정지된_계좌면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": 1000})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("금액이_0_이하이면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/withdraw", ownerID,
			map[string]int{"amount": -1})
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestSuspendAccount(t *testing.T) {
	t.Run("정상_계좌를_정지하면_204를_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		getResp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		getBody := decodeBody(t, getResp)
		require.Equal(t, "SUSPENDED", getBody["status"])
	})

	t.Run("존재하지_않는_계좌면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/suspend", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("이미_정지된_계좌면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestReactivateAccount(t *testing.T) {
	t.Run("정지된_계좌를_재개하면_204를_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/suspend", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/reactivate", ownerID, nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		getResp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		getBody := decodeBody(t, getResp)
		require.Equal(t, "ACTIVE", getBody["status"])
	})

	t.Run("존재하지_않는_계좌면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/reactivate", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("활성_계좌를_재개하면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/reactivate", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestCloseAccount(t *testing.T) {
	t.Run("잔액이_0인_계좌를_종료하면_204를_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)
		require.Equal(t, http.StatusNoContent, resp.StatusCode)

		getResp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		getBody := decodeBody(t, getResp)
		require.Equal(t, "CLOSED", getBody["status"])
	})

	t.Run("존재하지_않는_계좌면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts/non-existent/close", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("잔액이_0이_아니면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/deposit", ownerID,
			map[string]int{"amount": 5000})

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})

	t.Run("이미_종료된_계좌면_400을_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")
		doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)

		resp := doRequest(t, http.MethodPost, "/accounts/"+account["accountId"].(string)+"/close", ownerID, nil)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
	})
}

func TestGetAccount(t *testing.T) {
	t.Run("존재하는_계좌를_조회하면_200과_계좌_정보를_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), ownerID, nil)
		require.Equal(t, http.StatusOK, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, account["accountId"], body["accountId"])
		require.Equal(t, ownerID, body["ownerId"])
		require.NotEmpty(t, body["updatedAt"])
	})

	t.Run("존재하지_않는_계좌면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodGet, "/accounts/non-existent", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("다른_소유자가_조회하면_404를_반환한다", func(t *testing.T) {
		account := createAccount(t, ownerID, "KRW")

		resp := doRequest(t, http.MethodGet, "/accounts/"+account["accountId"].(string), otherOwnerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})
}

func TestGetTransactions(t *testing.T) {
	t.Run("거래_내역을_페이지네이션과_함께_반환한다", func(t *testing.T) {
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

	t.Run("존재하지_않는_계좌면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodGet, "/accounts/non-existent/transactions", ownerID, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("take를_초과한_페이지_조회는_빈_배열을_반환한다", func(t *testing.T) {
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
