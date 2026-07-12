package test

import (
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// issueCard는 카드를 발급하고 성공 응답 본문을 반환한다.
func issueCard(t *testing.T, ownerID, accountID, brand string) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/cards", ownerID,
		map[string]string{"accountId": accountID, "brand": brand})
}

// getCardStatus는 카드를 조회해 status를 반환한다(조회 실패 시 빈 문자열).
func getCardStatus(t *testing.T, ownerID, cardID string) string {
	t.Helper()
	resp := doRequest(t, http.MethodGet, "/cards/"+cardID, ownerID, nil)
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return ""
	}
	body := decodeBody(t, resp)
	if s, ok := body["status"].(string); ok {
		return s
	}
	return ""
}

// waitForCardStatus는 비동기 Integration Event 반영을 기다리며 카드 상태를 폴링한다.
func waitForCardStatus(t *testing.T, ownerID, cardID, want string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var last string
	for time.Now().Before(deadline) {
		last = getCardStatus(t, ownerID, cardID)
		if last == want {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("card %s status = %q, want %q (timed out)", cardID, last, want)
}

// TestIssueCard는 동기 Adapter/ACL 경로를 검증한다 — 카드 발급이 계좌 상태에 게이트된다.
func TestIssueCard(t *testing.T) {
	const owner = "card-owner-1"

	t.Run("활성_계좌에_카드를_발급하면_201과_ACTIVE_카드를_반환한다", func(t *testing.T) {
		account := createAccount(t, owner, "KRW")
		accountID := account["accountId"].(string)

		resp := issueCard(t, owner, accountID, "VISA")
		require.Equal(t, http.StatusCreated, resp.StatusCode)

		body := decodeBody(t, resp)
		require.Equal(t, accountID, body["accountId"])
		require.Equal(t, owner, body["ownerId"])
		require.Equal(t, "VISA", body["brand"])
		require.Equal(t, "ACTIVE", body["status"])
		require.NotEmpty(t, body["cardId"])
	})

	t.Run("존재하지_않는_계좌면_404_LINKED_ACCOUNT_NOT_FOUND를_반환한다", func(t *testing.T) {
		resp := issueCard(t, owner, "non-existent-account", "VISA")
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "LINKED_ACCOUNT_NOT_FOUND", body["code"])
	})

	t.Run("다른_소유자의_계좌면_404를_반환한다", func(t *testing.T) {
		account := createAccount(t, owner, "KRW")
		accountID := account["accountId"].(string)

		// ACL이 owner 매칭에 실패해 "계좌 없음"으로 번역 → LINKED_ACCOUNT_NOT_FOUND.
		resp := issueCard(t, "card-owner-other", accountID, "VISA")
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("정지된_계좌면_400_CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT를_반환한다", func(t *testing.T) {
		account := createAccount(t, owner, "KRW")
		accountID := account["accountId"].(string)
		doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)

		resp := issueCard(t, owner, accountID, "VISA")
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT", body["code"])
	})
}

func TestGetCard(t *testing.T) {
	const owner = "card-owner-get"

	t.Run("발급한_카드를_조회하면_200과_카드_정보를_반환한다", func(t *testing.T) {
		account := createAccount(t, owner, "KRW")
		accountID := account["accountId"].(string)
		issued := decodeBody(t, issueCard(t, owner, accountID, "MASTER"))
		cardID := issued["cardId"].(string)

		resp := doRequest(t, http.MethodGet, "/cards/"+cardID, owner, nil)
		require.Equal(t, http.StatusOK, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, cardID, body["cardId"])
		require.Equal(t, "MASTER", body["brand"])
	})

	t.Run("존재하지_않는_카드면_404를_반환한다", func(t *testing.T) {
		resp := doRequest(t, http.MethodGet, "/cards/non-existent", owner, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("다른_소유자가_조회하면_404를_반환한다", func(t *testing.T) {
		account := createAccount(t, owner, "KRW")
		accountID := account["accountId"].(string)
		issued := decodeBody(t, issueCard(t, owner, accountID, "VISA"))
		cardID := issued["cardId"].(string)

		resp := doRequest(t, http.MethodGet, "/cards/"+cardID, "card-owner-intruder", nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})
}

// TestCardReactsToAccountSuspended는 비동기 Integration Event 반응을 검증한다 —
// 계좌를 정지하면 그 계좌의 ACTIVE 카드가 SUSPENDED로 전환된다.
func TestCardReactsToAccountSuspended(t *testing.T) {
	const owner = "card-owner-suspend"
	account := createAccount(t, owner, "KRW")
	accountID := account["accountId"].(string)

	card1 := decodeBody(t, issueCard(t, owner, accountID, "VISA"))["cardId"].(string)
	card2 := decodeBody(t, issueCard(t, owner, accountID, "MASTER"))["cardId"].(string)

	resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
	require.Equal(t, http.StatusNoContent, resp.StatusCode)

	waitForCardStatus(t, owner, card1, "SUSPENDED")
	waitForCardStatus(t, owner, card2, "SUSPENDED")
}

// TestCardReactsToAccountClosed는 계좌 해지 시 카드가 CANCELLED로 전환되는지 검증한다.
// SUSPENDED 카드도 CANCELLED로 전환된다(ACTIVE·SUSPENDED 모두 대상).
func TestCardReactsToAccountClosed(t *testing.T) {
	const owner = "card-owner-close"
	account := createAccount(t, owner, "KRW")
	accountID := account["accountId"].(string)

	activeCard := decodeBody(t, issueCard(t, owner, accountID, "VISA"))["cardId"].(string)

	// 먼저 정지시켜 SUSPENDED 카드를 만든 뒤 재개하고, 다시 발급해 ACTIVE·과거정지 카드를 섞는다.
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
	waitForCardStatus(t, owner, activeCard, "SUSPENDED")
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/reactivate", owner, nil)
	// 재개해도 카드는 자동 복구되지 않는다(정지→재개 대칭 이벤트는 설계상 없음) — SUSPENDED 유지.
	require.Equal(t, "SUSPENDED", getCardStatus(t, owner, activeCard))

	newCard := decodeBody(t, issueCard(t, owner, accountID, "MASTER"))["cardId"].(string)

	resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/close", owner, nil)
	require.Equal(t, http.StatusNoContent, resp.StatusCode)

	// SUSPENDED 카드와 ACTIVE 카드 모두 CANCELLED가 된다.
	waitForCardStatus(t, owner, activeCard, "CANCELLED")
	waitForCardStatus(t, owner, newCard, "CANCELLED")
}

// TestCardCascadeIsIdempotent는 같은 계좌를 반복 정지 시도해도(재수신 모사) 카드 상태가
// 안정적으로 유지되는지 확인한다 — ACTIVE 카드만 처리하므로 재적용이 무해하다.
func TestCardCascadeIsIdempotent(t *testing.T) {
	const owner = "card-owner-idem"
	account := createAccount(t, owner, "KRW")
	accountID := account["accountId"].(string)
	cardID := decodeBody(t, issueCard(t, owner, accountID, "VISA"))["cardId"].(string)

	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
	waitForCardStatus(t, owner, cardID, "SUSPENDED")

	// 이미 정지된 계좌를 다시 정지 시도하면 400(도메인 규칙)이지만, 설령 이벤트가 재발행돼도
	// 카드는 SUSPENDED로 유지된다.
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
	require.Equal(t, "SUSPENDED", getCardStatus(t, owner, cardID))
}
