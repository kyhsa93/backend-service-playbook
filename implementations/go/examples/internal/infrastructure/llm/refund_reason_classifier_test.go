package llm_test

import (
	"context"
	"testing"

	"github.com/example/account-service/internal/domain/payment"
	"github.com/example/account-service/internal/infrastructure/llm"
)

func TestRefundReasonClassifierImpl_Classify_ValidCategory_ReturnsIt(t *testing.T) {
	server := ollamaChatServer(`{"category":"DEFECTIVE_PRODUCT"}`)
	defer server.Close()
	classifier := llm.NewRefundReasonClassifierImpl(server.URL, "test-model")

	category := classifier.Classify(context.Background(), "The item arrived broken")

	if category != payment.RefundReasonCategoryDefectiveProduct {
		t.Fatalf("Classify() = %v, want DEFECTIVE_PRODUCT", category)
	}
}

func TestRefundReasonClassifierImpl_Classify_OutOfTaxonomyCategory_FallsBackToOther(t *testing.T) {
	server := ollamaChatServer(`{"category":"NOT_A_REAL_CATEGORY"}`)
	defer server.Close()
	classifier := llm.NewRefundReasonClassifierImpl(server.URL, "test-model")

	category := classifier.Classify(context.Background(), "Some reason")

	if category != payment.RefundReasonCategoryOther {
		t.Fatalf("Classify() = %v, want OTHER", category)
	}
}

func TestRefundReasonClassifierImpl_Classify_MalformedOutput_FallsBackToOther(t *testing.T) {
	server := ollamaChatServer(`not valid json`)
	defer server.Close()
	classifier := llm.NewRefundReasonClassifierImpl(server.URL, "test-model")

	category := classifier.Classify(context.Background(), "Anything")

	if category != payment.RefundReasonCategoryOther {
		t.Fatalf("Classify() = %v, want OTHER", category)
	}
}

func TestRefundReasonClassifierImpl_Classify_OllamaUnreachable_FallsBackToOtherRatherThanBlocking(t *testing.T) {
	classifier := llm.NewRefundReasonClassifierImpl(unreachableServer(), "test-model")

	category := classifier.Classify(context.Background(), "Anything")

	if category != payment.RefundReasonCategoryOther {
		t.Fatalf("Classify() = %v, want OTHER", category)
	}
}
