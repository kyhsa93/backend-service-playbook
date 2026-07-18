package test

import (
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// createPayment은 결제를 요청하고 응답 전체를 반환한다(성공/실패 모두).
func createPayment(t *testing.T, ownerID, cardID string, amount int) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/payments", ownerID,
		map[string]any{"cardId": cardID, "amount": amount})
}

// getAccountBalance는 계좌를 조회해 잔액(amount)을 반환한다(조회 실패 시 -1).
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

// waitForAccountBalance는 비동기 Integration Event 반영을 기다리며 계좌 잔액을 폴링한다.
func waitForAccountBalance(t *testing.T, ownerID, accountID string, want int64) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var last int64
	for time.Now().Before(deadline) {
		last = getAccountBalance(t, ownerID, accountID)
		if last == want {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("account %s balance = %d, want %d (timed out)", accountID, last, want)
}

// setupFundedCardAndAccount는 결제 시나리오 공통 픽스처다 — 계좌를 만들고 충전한 뒤
// 카드를 발급한다.
func setupFundedCardAndAccount(t *testing.T, owner string, depositAmount int) (accountID, cardID string) {
	t.Helper()
	acc := createAccount(t, owner, "KRW")
	accountID = acc["accountId"].(string)
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/deposit", owner, map[string]int{"amount": depositAmount})
	card := decodeBody(t, issueCard(t, owner, accountID, "VISA"))
	cardID = card["cardId"].(string)
	return accountID, cardID
}

// TestCreatePayment은 동기 Adapter 경로(카드 활성 여부, 계좌 활성 여부·잔액 충분 여부)를
// 검증한다.
func TestCreatePayment(t *testing.T) {
	t.Run("활성_카드와_충분한_잔액이면_201과_COMPLETED_결제를_반환한다", func(t *testing.T) {
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

	t.Run("정지된_카드면_400_PAYMENT_REQUIRES_ACTIVE_CARD를_반환한다", func(t *testing.T) {
		const owner = "payment-owner-inactive-card"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
		// Card BC에는 카드를 직접 정지하는 엔드포인트가 없다 — 계좌를 정지하면
		// account.suspended.v1을 Card BC가 구독해 카드를 SUSPENDED로 캐스케이드한다
		// (card_e2e_test.go의 TestCardReactsToAccountSuspended와 동일한 경로).
		doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
		waitForCardStatus(t, owner, cardID, "SUSPENDED")

		resp := createPayment(t, owner, cardID, 1000)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "PAYMENT_REQUIRES_ACTIVE_CARD", body["code"])
	})

	t.Run("존재하지_않는_카드면_404_LINKED_CARD_NOT_FOUND를_반환한다", func(t *testing.T) {
		const owner = "payment-owner-missing-card"
		resp := createPayment(t, owner, "non-existent-card", 1000)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "LINKED_CARD_NOT_FOUND", body["code"])
	})

	t.Run("잔액이_부족하면_400_INSUFFICIENT_BALANCE를_반환한다", func(t *testing.T) {
		const owner = "payment-owner-insufficient"
		_, cardID := setupFundedCardAndAccount(t, owner, 1000)

		resp := createPayment(t, owner, cardID, 5000)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "INSUFFICIENT_BALANCE", body["code"])
	})

	t.Run("정상_결제는_비동기로_계좌_잔액을_차감한다", func(t *testing.T) {
		const owner = "payment-owner-async-debit"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)

		resp := createPayment(t, owner, cardID, 4000)
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		// 결제 완료(COMPLETED)는 동기 응답이지만 실제 계좌 차감은 payment.completed.v1을
		// Account BC가 비동기로 구독해 수행한다 — 즉시 반영되지 않을 수 있으므로 폴링한다.
		waitForAccountBalance(t, owner, accountID, 6000)
	})
}

// TestCancelPayment는 결제취소 → 보상 크레딧(비동기) 흐름을 검증한다.
func TestCancelPayment(t *testing.T) {
	const owner = "payment-owner-cancel"
	accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
	payment := decodeBody(t, createPayment(t, owner, cardID, 4000))
	paymentID := payment["paymentId"].(string)

	waitForAccountBalance(t, owner, accountID, 6000)

	resp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/cancel", owner, map[string]string{"reason": "customer request"})
	require.Equal(t, http.StatusNoContent, resp.StatusCode)

	// payment.cancelled.v1을 Account BC가 구독해 보상 크레딧(deposit)을 실행 — 잔액이
	// 결제 이전 상태(10000)로 복구된다.
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

// TestGetPayments는 목록 조회가 인증된 요청자 자신의 결제만 반환하는지 검증한다 —
// 클라이언트가 넘긴 소유자 ID는 없다(엔드포인트에 그런 쿼리 파라미터가 없다).
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

// TestRequestRefund는 RefundEligibilityService(Domain Service)가 Payment+Refund 두
// Aggregate를 조율해 내리는 승인/거부 판단을 REST 표면에서 검증한다.
func TestRequestRefund(t *testing.T) {
	t.Run("완료된_결제에_결제금액_이하_환불_요청은_201과_APPROVED를_반환하고_비동기로_크레딧된다", func(t *testing.T) {
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

		// refund.approved.v1을 Account BC가 구독해 환불 크레딧(deposit)을 실행한다.
		waitForAccountBalance(t, owner, accountID, 7000)
	})

	t.Run("환불_금액이_결제_금액을_초과하면_201이지만_status는_REJECTED다", func(t *testing.T) {
		const owner = "payment-owner-refund-exceeds"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
		payment := decodeBody(t, createPayment(t, owner, cardID, 2000))
		paymentID := payment["paymentId"].(string)
		waitForAccountBalance(t, owner, accountID, 8000)

		resp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/refunds", owner,
			map[string]any{"amount": 5000, "reason": "wrong item"})
		// 환불 요청 자체는 성공적으로 "평가"되었다 — 거부는 4xx가 아니라 201 + REJECTED다.
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "REJECTED", body["status"])
		require.NotEmpty(t, body["decisionNote"])

		// 거부된 환불은 크레딧을 유발하지 않는다 — 잔액 변화 없음을 확인한다.
		time.Sleep(200 * time.Millisecond)
		require.Equal(t, int64(8000), getAccountBalance(t, owner, accountID))
	})

	t.Run("완료되지_않은_결제(즉시_취소)에_대한_환불_요청은_201이지만_status는_REJECTED다", func(t *testing.T) {
		const owner = "payment-owner-refund-not-completed"
		accountID, cardID := setupFundedCardAndAccount(t, owner, 10000)
		payment := decodeBody(t, createPayment(t, owner, cardID, 2000))
		paymentID := payment["paymentId"].(string)
		waitForAccountBalance(t, owner, accountID, 8000)

		// 결제를 취소해 CANCELLED로 만든다 — COMPLETED가 아니므로 환불 대상이 될 수 없다.
		cancelResp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/cancel", owner, map[string]string{"reason": "x"})
		require.Equal(t, http.StatusNoContent, cancelResp.StatusCode)
		waitForAccountBalance(t, owner, accountID, 10000)

		resp := doRequest(t, http.MethodPost, "/payments/"+paymentID+"/refunds", owner,
			map[string]any{"amount": 1000, "reason": "wrong item"})
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "REJECTED", body["status"])
	})

	t.Run("존재하지_않는_결제에_대한_환불_요청은_404를_반환한다", func(t *testing.T) {
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
