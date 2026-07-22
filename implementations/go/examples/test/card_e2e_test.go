package test

import (
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// issueCard issues a card and returns the successful response body.
func issueCard(t *testing.T, ownerID, accountID, brand string) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/cards", ownerID,
		map[string]string{"accountId": accountID, "brand": brand})
}

// getCardStatus looks up the card and returns its status (empty string on
// lookup failure).
func getCardStatus(t *testing.T, ownerID, cardID string) string {
	t.Helper()
	resp := doRequest(t, http.MethodGet, "/cards/"+cardID, ownerID, nil)
	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		return ""
	}
	body := decodeBody(t, resp)
	if s, ok := body["status"].(string); ok {
		return s
	}
	return ""
}

// waitForCardStatus polls the card status while waiting for the asynchronous
// Integration Event to be applied.
//
// The Outbox → SQS → Handler path takes far longer round-trip than the old
// synchronous drain (immediate application), since the Poller's 1-second
// tick, the Consumer's 5-second long polling, and LocalStack's own latency
// all stack up — the budget is set to 30 seconds (150 iterations at 200ms
// intervals, same as nestjs) to absorb the 2-4 second round trip measured
// against the nestjs implementation.
func waitForCardStatus(t *testing.T, ownerID, cardID, want string) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	var last string
	for time.Now().Before(deadline) {
		last = getCardStatus(t, ownerID, cardID)
		if last == want {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("card %s status = %q, want %q (timed out)", cardID, last, want)
}

// TestIssueCard verifies the synchronous Adapter/ACL path — card issuance is
// gated on account state.
func TestIssueCard(t *testing.T) {
	const owner = "card-owner-1"

	t.Run("issuing_a_card_to_an_active_account_returns_201_and_an_ACTIVE_card", func(t *testing.T) {
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

	t.Run("nonexistent_account_returns_404_LINKED_ACCOUNT_NOT_FOUND", func(t *testing.T) {
		resp := issueCard(t, owner, "non-existent-account", "VISA")
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "LINKED_ACCOUNT_NOT_FOUND", body["code"])
	})

	t.Run("another_owners_account_returns_404", func(t *testing.T) {
		account := createAccount(t, owner, "KRW")
		accountID := account["accountId"].(string)

		// The ACL fails to match the owner and translates it as "account not
		// found" -> LINKED_ACCOUNT_NOT_FOUND.
		resp := issueCard(t, "card-owner-other", accountID, "VISA")
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("suspended_account_returns_400_CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT", func(t *testing.T) {
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

	t.Run("looking_up_an_issued_card_returns_200_and_card_info", func(t *testing.T) {
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

	t.Run("nonexistent_card_returns_404", func(t *testing.T) {
		resp := doRequest(t, http.MethodGet, "/cards/non-existent", owner, nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})

	t.Run("lookup_by_another_owner_returns_404", func(t *testing.T) {
		account := createAccount(t, owner, "KRW")
		accountID := account["accountId"].(string)
		issued := decodeBody(t, issueCard(t, owner, accountID, "VISA"))
		cardID := issued["cardId"].(string)

		resp := doRequest(t, http.MethodGet, "/cards/"+cardID, "card-owner-intruder", nil)
		require.Equal(t, http.StatusNotFound, resp.StatusCode)
	})
}

// TestCardReactsToAccountSuspended verifies the asynchronous Integration
// Event reaction — suspending an account transitions its ACTIVE cards to
// SUSPENDED.
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

// TestCardReactsToAccountClosed verifies that cards transition to CANCELLED
// when an account is closed. SUSPENDED cards also transition to CANCELLED
// (both ACTIVE and SUSPENDED are targeted).
func TestCardReactsToAccountClosed(t *testing.T) {
	const owner = "card-owner-close"
	account := createAccount(t, owner, "KRW")
	accountID := account["accountId"].(string)

	activeCard := decodeBody(t, issueCard(t, owner, accountID, "VISA"))["cardId"].(string)

	// First suspend to create a SUSPENDED card, then reactivate, then issue
	// again to mix an ACTIVE card with a previously-suspended one.
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
	waitForCardStatus(t, owner, activeCard, "SUSPENDED")
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/reactivate", owner, nil)
	// Reactivation does not automatically restore the card (there is no
	// suspend->reactivate symmetric event by design) — it stays SUSPENDED.
	require.Equal(t, "SUSPENDED", getCardStatus(t, owner, activeCard))

	newCard := decodeBody(t, issueCard(t, owner, accountID, "MASTER"))["cardId"].(string)

	resp := doRequest(t, http.MethodPost, "/accounts/"+accountID+"/close", owner, nil)
	require.Equal(t, http.StatusNoContent, resp.StatusCode)

	// Both the SUSPENDED card and the ACTIVE card become CANCELLED.
	waitForCardStatus(t, owner, activeCard, "CANCELLED")
	waitForCardStatus(t, owner, newCard, "CANCELLED")
}

// TestCardCascadeIsIdempotent checks that card state remains stable even
// when the same account is repeatedly suspended (simulating redelivery) —
// since only ACTIVE cards are processed, reapplication is harmless.
func TestCardCascadeIsIdempotent(t *testing.T) {
	const owner = "card-owner-idem"
	account := createAccount(t, owner, "KRW")
	accountID := account["accountId"].(string)
	cardID := decodeBody(t, issueCard(t, owner, accountID, "VISA"))["cardId"].(string)

	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
	waitForCardStatus(t, owner, cardID, "SUSPENDED")

	// Attempting to suspend an already-suspended account returns 400 (a
	// domain rule), but even if the event were republished, the card stays
	// SUSPENDED.
	doRequest(t, http.MethodPost, "/accounts/"+accountID+"/suspend", owner, nil)
	require.Equal(t, "SUSPENDED", getCardStatus(t, owner, cardID))
}
