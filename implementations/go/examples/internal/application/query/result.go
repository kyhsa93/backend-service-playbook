package query

import "time"

type MoneyResult struct {
	Amount   int64
	Currency string
}

type GetAccountResult struct {
	AccountID string
	OwnerID   string
	Email     string
	Balance   MoneyResult
	Status    string
	CreatedAt time.Time
	UpdatedAt time.Time
}

type TransactionSummary struct {
	TransactionID string
	Type          string
	Amount        MoneyResult
	// MerchantName/Category are optional (empty = absent) — see
	// account.Transaction's doc comments for when each is set.
	MerchantName string
	Category     string
	CreatedAt    time.Time
}

type GetTransactionsResult struct {
	Transactions []TransactionSummary
	Count        int
}

type SpendingAnalysisResult struct {
	AnalysisMonth           string
	TotalAmount             int64
	TransactionCount        int
	AverageAmount           int64
	ChangeFromPreviousMonth int
	Trend                   string
	CreatedAt               time.Time
}

type SpendingForecastResult struct {
	ForecastMonth     string
	PredictedAmount   int64
	Confidence        string
	HistoryMonthsUsed int
	CreatedAt         time.Time
}
