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

	_ "github.com/lib/pq"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/example/account-service/internal/infrastructure/persistence"
	httphandler "github.com/example/account-service/internal/interface/http"
)

var testServer *httptest.Server

const (
	ownerID      = "owner-1"
	otherOwnerID = "owner-2"
)

func TestMain(m *testing.M) {
	os.Exit(runTests(m))
}

func runTests(m *testing.M) int {
	ctx := context.Background()

	container, err := postgres.Run(ctx, "postgres:16-alpine",
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
	defer container.Terminate(ctx)

	connStr, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		panic(err)
	}

	db, err := sql.Open("postgres", connStr)
	if err != nil {
		panic(err)
	}
	defer db.Close()

	if err := waitForDB(db, 30, time.Second); err != nil {
		panic(fmt.Sprintf("db did not become ready: %v", err))
	}

	schema, err := os.ReadFile(filepath.Join("..", "migrations", "0001_init.sql"))
	if err != nil {
		panic(err)
	}
	if _, err := db.Exec(string(schema)); err != nil {
		panic(fmt.Sprintf("failed to apply schema: %v", err))
	}

	repo := persistence.NewAccountRepository(db)
	mux := httphandler.NewRouter(repo)
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
		req.Header.Set("X-User-Id", userID)
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
	resp := doRequest(t, http.MethodPost, "/accounts", ownerID, map[string]string{"currency": currency})
	require.Equal(t, http.StatusCreated, resp.StatusCode)
	return decodeBody(t, resp)
}

func TestCreateAccount(t *testing.T) {
	t.Run("생성_요청이_유효하면_201과_계좌_정보를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodPost, "/accounts", ownerID, map[string]string{"currency": "KRW"})
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, ownerID, body["ownerId"])
		require.Equal(t, "ACTIVE", body["status"])
		require.NotEmpty(t, body["accountId"])
		require.NotEmpty(t, body["createdAt"])
		balance := body["balance"].(map[string]any)
		require.Equal(t, float64(0), balance["amount"])
		require.Equal(t, "KRW", balance["currency"])
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
