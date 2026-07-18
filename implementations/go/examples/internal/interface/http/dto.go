package http

import "time"

type SignUpRequest struct {
	UserID   string `json:"userId"`
	Password string `json:"password"`
}

type SignInRequest struct {
	UserID   string `json:"userId"`
	Password string `json:"password"`
}

type SignInResponse struct {
	AccessToken string `json:"accessToken"`
}

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

type CreatePaymentRequest struct {
	CardID string `json:"cardId"`
	Amount int64  `json:"amount"`
}

type CancelPaymentRequest struct {
	Reason string `json:"reason"`
}

type PaymentResponse struct {
	PaymentID string    `json:"paymentId"`
	CardID    string    `json:"cardId"`
	AccountID string    `json:"accountId"`
	OwnerID   string    `json:"ownerId"`
	Amount    int64     `json:"amount"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"createdAt"`
}

type GetPaymentsResponse struct {
	Payments []PaymentResponse `json:"payments"`
	Count    int               `json:"count"`
}

type RequestRefundRequest struct {
	Amount int64  `json:"amount"`
	Reason string `json:"reason"`
}

type RefundResponse struct {
	RefundID     string    `json:"refundId"`
	PaymentID    string    `json:"paymentId"`
	Amount       int64     `json:"amount"`
	Reason       string    `json:"reason"`
	Status       string    `json:"status"`
	DecisionNote string    `json:"decisionNote,omitempty"`
	CreatedAt    time.Time `json:"createdAt"`
}

type GetRefundsResponse struct {
	Refunds []RefundResponse `json:"refunds"`
	Count   int              `json:"count"`
}
