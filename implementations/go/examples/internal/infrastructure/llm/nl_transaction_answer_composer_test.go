package llm_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/infrastructure/llm"
)

func sampleTransactions() []query.TransactionSummary {
	return []query.TransactionSummary{
		{
			TransactionID: "t1",
			Type:          string(account.TransactionTypeDeposit),
			Amount:        query.MoneyResult{Amount: 1000, Currency: "KRW"},
		},
	}
}

func TestNlTransactionAnswerComposerImpl_Compose_ModelAnswers_ReturnsTheTrimmedAnswer(t *testing.T) {
	server := ollamaChatServer("  You deposited 1000 KRW.  ")
	defer server.Close()
	composer := llm.NewNlTransactionAnswerComposerImpl(server.URL, "test-model")

	answer := composer.Compose(context.Background(), "How much did I deposit?", sampleTransactions())

	if answer != "You deposited 1000 KRW." {
		t.Fatalf("Compose() = %q, want trimmed answer", answer)
	}
}

func TestNlTransactionAnswerComposerImpl_Compose_OllamaCallFails_FallsBackToAPlainSummaryNamingTheActualCount(t *testing.T) {
	composer := llm.NewNlTransactionAnswerComposerImpl(unreachableServer(), "test-model")

	answer := composer.Compose(context.Background(), "How much did I deposit?", sampleTransactions())

	if !strings.Contains(answer, "Found 1 matching transaction(s)") {
		t.Fatalf("Compose() = %q, want it to mention the actual count", answer)
	}
}

func TestNlTransactionAnswerComposerImpl_Compose_NoTransactionsAndTheCallFails_SaysSoPlainly(t *testing.T) {
	composer := llm.NewNlTransactionAnswerComposerImpl(unreachableServer(), "test-model")

	answer := composer.Compose(context.Background(), "How much did I withdraw?", nil)

	if answer != "No matching transactions were found." {
		t.Fatalf("Compose() = %q, want the plain no-matches fallback", answer)
	}
}

func TestNlTransactionAnswerComposerImpl_Compose_MalformedOllamaOutput_FallsBackRatherThanPassingItThrough(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("not valid json"))
	}))
	defer server.Close()
	composer := llm.NewNlTransactionAnswerComposerImpl(server.URL, "test-model")

	answer := composer.Compose(context.Background(), "anything", nil)

	if answer != "No matching transactions were found." {
		t.Fatalf("Compose() = %q, want the plain no-matches fallback", answer)
	}
}

func TestNlTransactionAnswerComposerImpl_Compose_OllamaRespondsWithNonOKStatus_FallsBackToAPlainSummary(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()
	composer := llm.NewNlTransactionAnswerComposerImpl(server.URL, "test-model")

	answer := composer.Compose(context.Background(), "How much did I deposit?", sampleTransactions())

	if !strings.Contains(answer, "Found 1 matching transaction(s)") {
		t.Fatalf("Compose() = %q, want it to mention the actual count", answer)
	}
}
