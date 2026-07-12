package http

import "time"

type CreateAccountRequest struct {
	Email    string `json:"email"`
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
	Email     string        `json:"email"`
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
	Email     string        `json:"email"`
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

type IssueCardRequest struct {
	AccountID string `json:"accountId"`
	Brand     string `json:"brand"`
}

type CardResponse struct {
	CardID    string    `json:"cardId"`
	AccountID string    `json:"accountId"`
	OwnerID   string    `json:"ownerId"`
	Brand     string    `json:"brand"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"createdAt"`
}

// ErrorResponse는 root docs/architecture/error-handling.md가 요구하는 표준 에러 응답
// JSON 스키마다: statusCode/code/message/error 네 필드를 항상 포함한다.
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}
