// Package http contains this service's Interface-layer HTTP handlers and the
// request/response DTOs they use. Every DTO field below carries a trailing
// doc comment — swag (github.com/swaggo/swag) reads it as the field's
// OpenAPI `description` when `swag init` generates the schema (see
// docs/architecture/api-documentation.md).
package http

import "time"

type SignUpRequest struct {
	UserID   string `json:"userId"`   // The user ID to register. Must be unique.
	Password string `json:"password"` // The account password. Must be at least 8 characters.
}

type SignInRequest struct {
	UserID   string `json:"userId"`   // The registered user ID.
	Password string `json:"password"` // The account password.
}

type SignInResponse struct {
	AccessToken string `json:"accessToken"` // A JWT bearer token. Send it as `Authorization: Bearer <accessToken>` on subsequent requests.
}

type CreateAccountRequest struct {
	Email    string `json:"email"`    // The account owner's email address. Used as the destination for notification emails.
	Currency string `json:"currency"` // The account's currency code (e.g. `KRW`, `USD`). Fixed for the account's lifetime.
}

type DepositRequest struct {
	Amount int64 `json:"amount"` // The amount to credit. Must be a positive integer.
}

type WithdrawRequest struct {
	Amount int64 `json:"amount"` // The amount to debit. Must be a positive integer not exceeding the current balance.
}

type TransferRequest struct {
	TargetAccountID string `json:"targetAccountId"` // The account ID to transfer into. Must differ from the source account in the URL path.
	Amount          int64  `json:"amount"`          // The amount to transfer. Must be a positive integer not exceeding the source account's balance.
}

type MoneyResponse struct {
	Amount   int64  `json:"amount"`   // The amount, in the currency's smallest unit (no decimal places).
	Currency string `json:"currency"` // The currency code (e.g. `KRW`, `USD`).
}

type CreateAccountResponse struct {
	AccountID string        `json:"accountId"` // The newly created account's ID.
	OwnerID   string        `json:"ownerId"`   // The authenticated requester's user ID.
	Email     string        `json:"email"`     // The account owner's email address.
	Balance   MoneyResponse `json:"balance"`   // The account balance. Always 0 immediately after creation.
	Status    string        `json:"status"`    // The account status (`ACTIVE`, `SUSPENDED`, or `CLOSED`).
	CreatedAt time.Time     `json:"createdAt"` // When the account was created.
}

type TransactionResponse struct {
	TransactionID string        `json:"transactionId"` // The transaction record's ID.
	AccountID     string        `json:"accountId"`     // The account this transaction belongs to.
	Type          string        `json:"type"`          // The transaction type (`DEPOSIT`, `WITHDRAWAL`, or `INTEREST`).
	Amount        MoneyResponse `json:"amount"`        // The transaction amount.
	CreatedAt     time.Time     `json:"createdAt"`     // When the transaction was recorded.
}

type TransferResponse struct {
	TransferID        string              `json:"transferId"`        // Correlates the source and target transactions raised by this single transfer.
	SourceTransaction TransactionResponse `json:"sourceTransaction"` // The `WITHDRAWAL` transaction recorded on the source account.
	TargetTransaction TransactionResponse `json:"targetTransaction"` // The `DEPOSIT` transaction recorded on the target account.
}

type GetAccountResponse struct {
	AccountID string        `json:"accountId"` // The account's ID.
	OwnerID   string        `json:"ownerId"`   // The account owner's user ID.
	Email     string        `json:"email"`     // The account owner's email address.
	Balance   MoneyResponse `json:"balance"`   // The current account balance.
	Status    string        `json:"status"`    // The account status (`ACTIVE`, `SUSPENDED`, or `CLOSED`).
	CreatedAt time.Time     `json:"createdAt"` // When the account was created.
	UpdatedAt time.Time     `json:"updatedAt"` // When the account was last modified.
}

type TransactionSummaryResponse struct {
	TransactionID string        `json:"transactionId"` // The transaction record's ID.
	Type          string        `json:"type"`          // The transaction type (`DEPOSIT`, `WITHDRAWAL`, or `INTEREST`).
	Amount        MoneyResponse `json:"amount"`        // The transaction amount.
	CreatedAt     time.Time     `json:"createdAt"`     // When the transaction was recorded.
}

type GetTransactionsResponse struct {
	Transactions []TransactionSummaryResponse `json:"transactions"` // The account's transactions, newest first.
	Count        int                          `json:"count"`        // The total number of transactions matching the filter (not just the current page's size).
}

type IssueCardRequest struct {
	AccountID string `json:"accountId"` // The active account to link the card to. Must be owned by the authenticated requester.
	Brand     string `json:"brand"`     // The card brand (e.g. `VISA`, `MASTER`).
}

type CardResponse struct {
	CardID    string    `json:"cardId"`    // The card's ID.
	AccountID string    `json:"accountId"` // The account this card is linked to.
	OwnerID   string    `json:"ownerId"`   // The card owner's user ID.
	Brand     string    `json:"brand"`     // The card brand (e.g. `VISA`, `MASTER`).
	Status    string    `json:"status"`    // The card status (`ACTIVE`, `SUSPENDED`, or `CANCELLED`).
	CreatedAt time.Time `json:"createdAt"` // When the card was issued.
}

// ErrorResponse is the standard error response JSON schema required by root
// docs/architecture/error-handling.md: it always includes the four fields
// statusCode/code/message/error.
type ErrorResponse struct {
	StatusCode int    `json:"statusCode"` // The HTTP status code.
	Code       string `json:"code"`       // A stable, machine-readable error code the client can branch on (e.g. `ACCOUNT_NOT_FOUND`). Unlike `message`, this never changes wording.
	Message    string `json:"message"`    // A human-readable description of the error.
	Error      string `json:"error"`      // The standard HTTP status text for `statusCode` (e.g. `Not Found`).
}

type CreatePaymentRequest struct {
	CardID string `json:"cardId"` // The active card to charge. Must be linked to an active account with sufficient balance.
	Amount int64  `json:"amount"` // The amount to charge. Must be a positive integer.
}

type CancelPaymentRequest struct {
	Reason string `json:"reason"` // Why the payment is being cancelled.
}

type PaymentResponse struct {
	PaymentID string    `json:"paymentId"` // The payment's ID.
	CardID    string    `json:"cardId"`    // The card used for this payment.
	AccountID string    `json:"accountId"` // The account debited (asynchronously) for this payment.
	OwnerID   string    `json:"ownerId"`   // The payment owner's user ID.
	Amount    int64     `json:"amount"`    // The charged amount.
	Status    string    `json:"status"`    // The payment status (`COMPLETED` or `CANCELLED`).
	CreatedAt time.Time `json:"createdAt"` // When the payment was created.
}

type GetPaymentsResponse struct {
	Payments []PaymentResponse `json:"payments"` // The requester's payments, newest first.
	Count    int               `json:"count"`    // The total number of payments matching the filter (not just the current page's size).
}

type RequestRefundRequest struct {
	Amount int64  `json:"amount"` // The amount to refund. Must be a positive integer not exceeding the original payment amount.
	Reason string `json:"reason"` // Why the refund is being requested.
}

type RefundResponse struct {
	RefundID     string    `json:"refundId"`               // The refund request's ID.
	PaymentID    string    `json:"paymentId"`              // The payment this refund was requested against.
	Amount       int64     `json:"amount"`                 // The requested refund amount.
	Reason       string    `json:"reason"`                 // Why the refund was requested.
	Status       string    `json:"status"`                 // The refund status (`APPROVED`, `REJECTED`, or `COMPLETED`) — check this, not just the HTTP status, since a rejection is still a 201 response.
	DecisionNote string    `json:"decisionNote,omitempty"` // Why the refund was rejected. Only present when `status` is `REJECTED`.
	CreatedAt    time.Time `json:"createdAt"`              // When the refund was requested.
}

type GetRefundsResponse struct {
	Refunds []RefundResponse `json:"refunds"` // The refunds requested against the payment, newest first.
	Count   int              `json:"count"`   // The total number of refunds matching the filter (not just the current page's size).
}
