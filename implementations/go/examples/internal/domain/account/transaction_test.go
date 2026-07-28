package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

func TestTransaction_Categorize_ReturnsANewTransactionWithTheCategorySet(t *testing.T) {
	original := account.Transaction{
		TransactionID: "transaction-1",
		AccountID:     "account-1",
		Type:          account.TransactionTypeWithdrawal,
		Amount:        account.Money{Amount: 5500, Currency: "KRW"},
		MerchantName:  "Starbucks Gangnam",
	}

	categorized := original.Categorize(account.TransactionCategoryFood)

	if categorized.Category != account.TransactionCategoryFood {
		t.Fatalf("Category = %v, want %v", categorized.Category, account.TransactionCategoryFood)
	}
	if categorized.TransactionID != original.TransactionID || categorized.MerchantName != original.MerchantName {
		t.Fatalf("Categorize() must preserve every other field, got %+v", categorized)
	}
	// Transaction is immutable — Categorize must not mutate the receiver.
	if original.Category != "" {
		t.Fatalf("original.Category = %v, want empty (unmutated)", original.Category)
	}
}
