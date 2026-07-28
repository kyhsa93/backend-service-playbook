package llm_test

import (
	"context"
	"testing"

	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/infrastructure/llm"
)

func TestTransactionAutoCategorizerImpl_Categorize_ValidCategory_ReturnsIt(t *testing.T) {
	server := ollamaChatServer(`{"category":"FOOD"}`)
	defer server.Close()
	categorizer := llm.NewTransactionAutoCategorizerImpl(server.URL, "test-model")

	category := categorizer.Categorize(context.Background(), "Starbucks Gangnam", 5500)

	if category != account.TransactionCategoryFood {
		t.Fatalf("Categorize() = %v, want FOOD", category)
	}
}

func TestTransactionAutoCategorizerImpl_Categorize_OutOfTaxonomyCategory_FallsBackToOther(t *testing.T) {
	server := ollamaChatServer(`{"category":"NOT_A_REAL_CATEGORY"}`)
	defer server.Close()
	categorizer := llm.NewTransactionAutoCategorizerImpl(server.URL, "test-model")

	category := categorizer.Categorize(context.Background(), "Unknown Payee", 1000)

	if category != account.TransactionCategoryOther {
		t.Fatalf("Categorize() = %v, want OTHER", category)
	}
}

func TestTransactionAutoCategorizerImpl_Categorize_MalformedOutput_FallsBackToOther(t *testing.T) {
	server := ollamaChatServer(`not valid json`)
	defer server.Close()
	categorizer := llm.NewTransactionAutoCategorizerImpl(server.URL, "test-model")

	category := categorizer.Categorize(context.Background(), "Anything", 1000)

	if category != account.TransactionCategoryOther {
		t.Fatalf("Categorize() = %v, want OTHER", category)
	}
}

func TestTransactionAutoCategorizerImpl_Categorize_OllamaUnreachable_FallsBackToOtherRatherThanBlocking(t *testing.T) {
	categorizer := llm.NewTransactionAutoCategorizerImpl(unreachableServer(), "test-model")

	category := categorizer.Categorize(context.Background(), "Anything", 1000)

	if category != account.TransactionCategoryOther {
		t.Fatalf("Categorize() = %v, want OTHER", category)
	}
}
