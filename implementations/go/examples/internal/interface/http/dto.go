package http

import "time"

type CreateAccountRequest struct {
	Currency string `json:"currency"`
}

type DepositRequest struct {
	Amount int64 `json:"amount"`
}

type WithdrawRequest struct {
	Amount int64 `json:"amount"`
}

type MoneyResponse struct {
	Amount   int64  `json:"amount"`
	Currency string `json:"currency"`
}

type CreateAccountResponse struct {
	AccountID string        `json:"accountId"`
	OwnerID   string        `json:"ownerId"`
	Balance   MoneyResponse `json:"balance"`
	Status    string        `json:"status"`
	CreatedAt time.Time     `json:"createdAt"`
}

type TransactionResponse struct {
	TransactionID string        `json:"transactionId"`
	AccountID     string        `json:"accountId"`
	Type          string        `json:"type"`
	Amount        MoneyResponse `json:"amount"`
	CreatedAt     time.Time     `json:"createdAt"`
}

type GetAccountResponse struct {
	AccountID string        `json:"accountId"`
	OwnerID   string        `json:"ownerId"`
	Balance   MoneyResponse `json:"balance"`
	Status    string        `json:"status"`
	CreatedAt time.Time     `json:"createdAt"`
	UpdatedAt time.Time     `json:"updatedAt"`
}

type TransactionSummaryResponse struct {
	TransactionID string        `json:"transactionId"`
	Type          string        `json:"type"`
	Amount        MoneyResponse `json:"amount"`
	CreatedAt     time.Time     `json:"createdAt"`
}

type GetTransactionsResponse struct {
	Transactions []TransactionSummaryResponse `json:"transactions"`
	Count        int                          `json:"count"`
}
