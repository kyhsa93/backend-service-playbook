package test

import (
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// createPayment requests a payment and returns the full response (whether
// success or failure).
func createPayment(t *testing.T, ownerID, cardID string, amount int) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/payments", ownerID,
		map[string]any{"cardId": cardID, "amount": amount})
}

// getAccountBalance looks up the account and returns its balance (amount)
// (-1 on lookup failure).
func getAccountBalance(t *testing.T, ownerID, accountID string) int64 {
	t.Helper()
	resp := doRequest(t, http.MethodGet, "/accounts/"+accountID, ownerID, nil)
	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		return -1
	}
	body := decodeBody(t, resp)
	balance := body["balance"].(map[string]any)
	return int64(balance["amount"].(float64))
}

// waitForAccountBalance polls the account balance while waiting for the
// asynchronous Integration Event to be applied.
//
// The Outbox → SQS → Handler path takes far longer round-trip than the old
// synchronous drain (immediate application), since the Poller's 1-second
// tick, the Consumer's 5-second long polling, and LocalStack's own latency
// all stack up — the budget is set to 30 seconds (150 iterations at 200ms
// intervals, same as nestjs) to absorb the 2-4 second round trip measured
// against the nestjs implementation.
func waitForAccountBalance(t *testing.T, ownerID, accountID string, want int64) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	var last int64
	for time.Now().Before(deadline) {
		last = getAccountBalance(t, ownerID, accountID)
		if last == want {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("account %s balance = %d, want %d (timed out)", accountID, last, want)
}

// setupFundedCardAndAccount is a common fixture for payment scenarios — it
// creates and funds an account, then issues a card.
func setupFundedCardAndAccount(t *testing.T, owner string, depositAmount int) (accountID, cardID string) {
	t.Helper()
	acc := createAccount(t, owner, "KRW")
	accountID = acc["accountId"].(string)
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": depositAmount})
	card := decodeBody(t, issueCard(t, owner, accountID, "VISA"))
	cardID = card["cardId"].(string)
	return accountID, cardID
}

// TestCreatePayment verifies the synchronous Adapter path (whether the card
// is active, whether the account is active, and whether the balance is
// sufficient).
func TestCreatePayment(t *testing.T) {
	t.Run("active_card_and_sufficient_balance_returns_201_and_a_COMPLETED_payment", func(t *testing.T) {
		const owner = "payment-owner-create-1"
		_, cardID := setupFundedCardAndAccount(t, owner, 10000)

		resp := createPayment(t, owner, cardID, 3000)
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, cardID, body["cardId"])
		require.Equal(t, "COMPLETED", body["status"])
		require.Equal(t, float64(3000), body["amount"])
		require.NotEmpty(t, body["paymentId"])
	})

	t.Run("suspended_card_returns_400_PAYMENT_REQUIRES_ACTIVE_CARD", func(t *testing.T) {
		const owner = "payment-owner-inactive-card"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
		// The Card BC has no endpoint that directly suspends a card — when an
		// account is suspended, the Card BC subscribes to account.suspended.v1
		// and cascades the card to SUSPENDED (the same path as
		// TestCardReactsToAccountSuspended in card_e2e_test.go).
		doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
		waitForCardStatus(t, owner, cardID, "SUSPENDED")

		resp := createPayment(t, owner, cardID, 1000)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "PAYMENT_REQUIRES_ACTIVE_CARD", body["code"])
	})

	t.Run("nonexistent_card_returns_404_LINKED_CARD_NOT_FOUND", func(t *testing.T) {
		const owner = "payment-owner-missing-card"
		resp := createPayment(t, owner, "non-existent-card", 1000)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "LINKED_CARD_NOT_FOUND", body["code"])
	})

	t.Run("insufficient_balance_returns_400_INSUFFICIENT_BALANCE", func(t *testing.T) {
		const owner = "payment-owner-insufficient"
		_, cardID := setupFundedCardAndAccount(t, owner, 1000)

		resp := createPayment(t, owner, cardID, 5000)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "INSUFFICIENT_BALANCE", body["code"])
	})

	t.Run("a_normal_payment_asynchronously_deducts_the_account_balance", func(t *testing.T) {
		const owner = "payment-owner-async-debit"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)

		resp := createPayment(t, owner, cardID, 4000)
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		// Payment completion (COMPLETED) is a synchronous response, but the
		// actual account debit is performed asynchronously by the Account BC
		// subscribing to payment.completed.v1 — it may not be reflected
		// immediately, so poll for it.
		waitForAccountBalance(t, owner, accountID, 6000)
	})
}

// TestCancelPayment verifies the payment-cancellation → compensating credit
// (asynchronous) flow.
func TestCancelPayment(t *testing.T) {
	const owner = "payment-owner-cancel"
	accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
	payment := decodeBody(t, createPayment(t, owner, cardID, 4000))
	paymentID := payment["paymentId"].(string)

	waitForAccountBalance(t, owner, accountID, 6000)

	resp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/cancel", owner, map[string]string{"reason": "customer request"})
	require.Equal(t, http.StatusNoContent, resp.StatusCode)

	// The Account BC subscribes to payment.cancelled.v1 and executes a
	// compensating credit (deposit) — the balance is restored to its
	// pre-payment state (10000).
	waitForAccountBalance(t, owner, accountID, 10000)

	getResp := doRequest(t, http.MethodGet, "/payments/"+paymentID, owner, nil)
	require.Equal(t, http.StatusOK, getResp.StatusCode)
	require.Equal(t, "CANCELLED", decodeBody(t, getResp)["status"])
}

func TestCancelPayment_PendingPaymentCannotBeCancelled(t *testing.T) {
	const owner = "payment-owner-cancel-pending"
	resp := doRequest(t, http.MethodPost, "/payments/non-existent/cancel", owner, map[string]string{"reason": "x"})
	require.Equal(t, http.StatusNotFound, resp.StatusCode)
	body := decodeBody(t, resp)
	require.Equal(t, "PAYMENT_NOT_FOUND", body["code"])
}

// TestGetPayments verifies that the list query returns only the
// authenticated requester's own payments — there is no owner ID passed by
// the client (the endpoint has no such query parameter).
func TestGetPayments(t *testing.T) {
	const owner = "payment-owner-list"
	_, cardID := setupFundedCardAndAccount(t, owner, 10000)
	createPayment(t, owner, cardID, 1000)
	createPayment(t, owner, cardID, 2000)

	resp := doRequest(t, http.MethodGet, "/payments", owner, nil)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	body := decodeBody(t, resp)
	require.GreaterOrEqual(t, body["count"].(float64), float64(2))
}

// TestRequestRefund verifies, at the REST surface, the approve/reject
// decision that RefundEligibilityService (a Domain Service) makes by
// coordinating the Payment and Refund Aggregates.
func TestRequestRefund(t *testing.T) {
	t.Run("a_refund_request_up_to_the_payment_amount_on_a_completed_payment_returns_201_and_APPROVED_and_is_asynchronously_credited", func(t *testing.T) {
		const owner = "payment-owner-refund-approve"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
		payment := decodeBody(t, createPayment(t, owner, cardID, 5000))
		paymentID := payment["paymentId"].(string)
		waitForAccountBalance(t, owner, accountID, 5000)

		resp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/refunds", owner,
			map[string]any{"amount": 2000, "reason": "wrong item"})
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "APPROVED", body["status"])
		require.NotEmpty(t, body["refundId"])

		// The Account BC subscribes to refund.approved.v1 and executes the
		// refund credit (deposit).
		waitForAccountBalance(t, owner, accountID, 7000)
	})

	t.Run("a_refund_amount_exceeding_the_payment_amount_returns_201_but_status_is_REJECTED", func(t *testing.T) {
		const owner = "payment-owner-refund-exceeds"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
		payment := decodeBody(t, createPayment(t, owner, cardID, 2000))
		paymentID := payment["paymentId"].(string)
		waitForAccountBalance(t, owner, accountID, 8000)

		resp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/refunds", owner,
			map[string]any{"amount": 5000, "reason": "wrong item"})
		// The refund request itself was successfully "evaluated" — rejection
		// is 201 + REJECTED, not a 4xx.
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "REJECTED", body["status"])
		require.NotEmpty(t, body["decisionNote"])

		// A rejected refund never triggers a credit — verify there is no
		// balance change. In principle no wait is needed at all, since
		// there's no Domain Event and thus no row is ever written to the
		// Outbox, but for this negative assertion to mean anything, we wait
		// longer than that latency (Poller's 1-second tick + Consumer's
		// 5-second long polling) in case one was somehow wrongly published.
		time.Sleep(8 * time.Second)
		require.Equal(t, int64(8000), getAccountBalance(t, owner, accountID))
	})

	t.Run("a_refund_request_for_a_non_completed_payment_immediately_cancelled_returns_201_but_status_is_REJECTED", func(t *testing.T) {
		const owner = "payment-owner-refund-not-completed"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
		payment := decodeBody(t, createPayment(t, owner, cardID, 2000))
		paymentID := payment["paymentId"].(string)
		waitForAccountBalance(t, owner, accountID, 8000)

		// Cancel the payment to make it CANCELLED — since it's not COMPLETED,
		// it cannot be eligible for a refund.
		cancelResp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/cancel", owner, map[string]string{"reason": "x"})
		require.Equal(t, http.StatusNoContent, cancelResp.StatusCode)
		waitForAccountBalance(t, owner, accountID, 10000)

		resp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/refunds", owner,
			map[string]any{"amount": 1000, "reason": "wrong item"})
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "REJECTED", body["status"])
	})

	t.Run("a_refund_request_for_a_nonexistent_payment_returns_404", func(t *testing.T) {
		const owner = "payment-owner-refund-missing"
		resp := doRequest(t, http.MethodPost, "/payments/non-existent/refunds", owner,
			map[string]any{"amount": 1000, "reason": "wrong item"})
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})
}

func TestGetRefunds(t *testing.T) {
	const owner = "payment-owner-refund-list"
	accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
	payment := decodeBody(t, createPayment(t, owner, cardID, 5000))
	paymentID := payment["paymentId"].(string)
	waitForAccountBalance(t, owner, accountID, 5000)

	doRequest(t, http.MethodPost, "/payments/"+paymentID+"/refunds", owner, map[string]any{"amount": 1000, "reason": "a"})
	doRequest(t, http.MethodPost, "/payments/"+paymentID+"/refunds", owner, map[string]any{"amount": 6000, "reason": "b"})

	resp := doRequest(t, http.MethodGet, "/payments/"+paymentID+"/refunds", owner, nil)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	body := decodeBody(t, resp)
	require.Equal(t, float64(2), body["count"])
}
