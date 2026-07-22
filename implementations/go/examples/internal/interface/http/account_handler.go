package http

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/interface/http/middleware"
)

type AccountHandler struct {
	createAccount     *command.CreateAccountHandler
	deposit           *command.DepositHandler
	withdraw          *command.WithdrawHandler
	transfer          *command.TransferHandler
	suspendAccount    *command.SuspendAccountHandler
	reactivateAccount *command.ReactivateAccountHandler
	closeAccount      *command.CloseAccountHandler
	getAccount        *query.GetAccountHandler
	getTransactions   *query.GetTransactionsHandler
}

func NewAccountHandler(
	createAccount *command.CreateAccountHandler,
	deposit *command.DepositHandler,
	withdraw *command.WithdrawHandler,
	transfer *command.TransferHandler,
	suspendAccount *command.SuspendAccountHandler,
	reactivateAccount *command.ReactivateAccountHandler,
	closeAccount *command.CloseAccountHandler,
	getAccount *query.GetAccountHandler,
	getTransactions *query.GetTransactionsHandler,
) *AccountHandler {
	return &AccountHandler{
		createAccount:     createAccount,
		deposit:           deposit,
		withdraw:          withdraw,
		transfer:          transfer,
		suspendAccount:    suspendAccount,
		reactivateAccount: reactivateAccount,
		closeAccount:      closeAccount,
		getAccount:        getAccount,
		getTransactions:   getTransactions,
	}
}

// CreateAccount opens a new account for the authenticated requester.
//
// @Summary		Open a new account
// @Description	Opens a new account for the authenticated requester with a 0 balance in the given currency.
// @Tags			Account
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			body	body		CreateAccountRequest	true	"Owner email and currency"
// @Success		201		{object}	CreateAccountResponse	"The account was created."
// @Failure		400		{object}	ErrorResponse			"Request validation failed (`VALIDATION_FAILED`) — e.g. an invalid or missing email."
// @Failure		401		{object}	ErrorResponse			"The bearer token is missing, malformed, or invalid."
// @Router			/accounts [post]
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	var body CreateAccountRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
		return
	}
	if !isValidEmail(body.Email) {
		writeValidationError(w, r, "email must be a valid, non-empty email address")
		return
	}
	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{
		RequesterID: requesterID,
		Email:       body.Email,
		Currency:    body.Currency,
	})
	if err != nil {
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, CreateAccountResponse{
		AccountID: a.AccountID,
		OwnerID:   a.OwnerID,
		Email:     a.Email,
		Balance:   MoneyResponse{Amount: a.Balance.Amount, Currency: a.Balance.Currency},
		Status:    string(a.Status),
		CreatedAt: a.CreatedAt,
	})
}

// isValidEmail performs minimal format validation that can be handled with
// the standard library alone. It's a deliberately simple check, to avoid
// adding an extra dependency.
func isValidEmail(email string) bool {
	email = strings.TrimSpace(email)
	if email == "" {
		return false
	}
	at := strings.Index(email, "@")
	if at <= 0 || at == len(email)-1 {
		return false
	}
	return strings.Contains(email[at+1:], ".")
}

// Deposit credits an amount into an account.
//
// @Summary		Deposit money into an account
// @Description	Credits the given amount to the account and records a `DEPOSIT` transaction.
// @Tags			Account
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			id		path		string					true	"The account ID"
// @Param			body	body		DepositRequest			true	"Deposit amount"
// @Success		201		{object}	TransactionResponse		"The deposit succeeded."
// @Failure		400		{object}	ErrorResponse			"One of: request validation failed (`VALIDATION_FAILED`), the amount is not a positive integer (`ACCOUNT_INVALID_AMOUNT`), or the account is not active (`ACCOUNT_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`)."
// @Failure		401		{object}	ErrorResponse			"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse			"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/deposit [post]
func (h *AccountHandler) Deposit(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	var body DepositRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
		return
	}
	tx, err := h.deposit.Handle(r.Context(), command.DepositCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
		Amount:      body.Amount,
	})
	if err != nil {
		writeAccountError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, TransactionResponse{
		TransactionID: tx.TransactionID,
		AccountID:     tx.AccountID,
		Type:          string(tx.Type),
		Amount:        MoneyResponse{Amount: tx.Amount.Amount, Currency: tx.Amount.Currency},
		CreatedAt:     tx.CreatedAt,
	})
}

// Withdraw debits an amount from an account.
//
// @Summary		Withdraw money from an account
// @Description	Debits the given amount from the account and records a `WITHDRAWAL` transaction.
// @Tags			Account
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			id		path		string					true	"The account ID"
// @Param			body	body		WithdrawRequest			true	"Withdrawal amount"
// @Success		201		{object}	TransactionResponse		"The withdrawal succeeded."
// @Failure		400		{object}	ErrorResponse			"One of: request validation failed (`VALIDATION_FAILED`), the amount is not a positive integer (`ACCOUNT_INVALID_AMOUNT`), the account is not active (`ACCOUNT_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`), or the balance is insufficient (`ACCOUNT_INSUFFICIENT_BALANCE`)."
// @Failure		401		{object}	ErrorResponse			"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse			"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/withdraw [post]
func (h *AccountHandler) Withdraw(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	var body WithdrawRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
		return
	}
	tx, err := h.withdraw.Handle(r.Context(), command.WithdrawCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
		Amount:      body.Amount,
	})
	if err != nil {
		writeAccountError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, TransactionResponse{
		TransactionID: tx.TransactionID,
		AccountID:     tx.AccountID,
		Type:          string(tx.Type),
		Amount:        MoneyResponse{Amount: tx.Amount.Amount, Currency: tx.Amount.Currency},
		CreatedAt:     tx.CreatedAt,
	})
}

// Transfer atomically moves money from the account in the URL to another account.
//
// @Summary		Transfer money to another account
// @Description	Atomically debits the source account and credits the target account with the given amount, recording one `WITHDRAWAL` and one `DEPOSIT` transaction.
// @Tags			Account
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			id		path		string				true	"The source account ID"
// @Param			body	body		TransferRequest		true	"Target account and transfer amount"
// @Success		201		{object}	TransferResponse	"The transfer succeeded."
// @Failure		400		{object}	ErrorResponse		"One of: request validation failed (`VALIDATION_FAILED`), the amount is not a positive integer (`ACCOUNT_INVALID_AMOUNT`), the source and target accounts are the same (`ACCOUNT_TRANSFER_SAME_ACCOUNT`), either account is not active (`ACCOUNT_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`/`ACCOUNT_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`), the currencies do not match (`ACCOUNT_CURRENCY_MISMATCH`), or the balance is insufficient (`ACCOUNT_INSUFFICIENT_BALANCE`)."
// @Failure		401		{object}	ErrorResponse		"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse		"No account exists with the given source or target account ID (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/transfer [post]
func (h *AccountHandler) Transfer(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	var body TransferRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
		return
	}
	result, err := h.transfer.Handle(r.Context(), command.TransferCommand{
		SourceAccountID: accountID,
		TargetAccountID: body.TargetAccountID,
		RequesterID:     requesterID,
		Amount:          body.Amount,
	})
	if err != nil {
		writeAccountError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, TransferResponse{
		TransferID: result.TransferID,
		SourceTransaction: TransactionResponse{
			TransactionID: result.SourceTransaction.TransactionID,
			AccountID:     result.SourceTransaction.AccountID,
			Type:          string(result.SourceTransaction.Type),
			Amount:        MoneyResponse{Amount: result.SourceTransaction.Amount.Amount, Currency: result.SourceTransaction.Amount.Currency},
			CreatedAt:     result.SourceTransaction.CreatedAt,
		},
		TargetTransaction: TransactionResponse{
			TransactionID: result.TargetTransaction.TransactionID,
			AccountID:     result.TargetTransaction.AccountID,
			Type:          string(result.TargetTransaction.Type),
			Amount:        MoneyResponse{Amount: result.TargetTransaction.Amount.Amount, Currency: result.TargetTransaction.Amount.Currency},
			CreatedAt:     result.TargetTransaction.CreatedAt,
		},
	})
}

// SuspendAccount suspends an active account.
//
// @Summary		Suspend an account
// @Description	Suspends an active account, blocking further deposits/withdrawals/transfers until it is reactivated.
// @Tags			Account
// @Produce		json
// @Security		BearerAuth
// @Param			id	path	string	true	"The account ID"
// @Success		204	"The account was suspended."
// @Failure		400	{object}	ErrorResponse	"Only an active account can be suspended (`ACCOUNT_SUSPEND_REQUIRES_ACTIVE_ACCOUNT`)."
// @Failure		401	{object}	ErrorResponse	"The bearer token is missing, malformed, or invalid."
// @Failure		404	{object}	ErrorResponse	"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/suspend [post]
func (h *AccountHandler) SuspendAccount(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	if err := h.suspendAccount.Handle(r.Context(), command.SuspendAccountCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
	}); err != nil {
		writeAccountError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ReactivateAccount moves a suspended account back to active.
//
// @Summary		Reactivate a suspended account
// @Description	Moves a suspended account back to active, restoring its ability to accept deposits/withdrawals/transfers.
// @Tags			Account
// @Produce		json
// @Security		BearerAuth
// @Param			id	path	string	true	"The account ID"
// @Success		204	"The account was reactivated."
// @Failure		400	{object}	ErrorResponse	"Only a suspended account can be reactivated (`ACCOUNT_REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT`)."
// @Failure		401	{object}	ErrorResponse	"The bearer token is missing, malformed, or invalid."
// @Failure		404	{object}	ErrorResponse	"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/reactivate [post]
func (h *AccountHandler) ReactivateAccount(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	if err := h.reactivateAccount.Handle(r.Context(), command.ReactivateAccountCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
	}); err != nil {
		writeAccountError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// CloseAccount permanently closes an account with a 0 balance.
//
// @Summary		Close an account
// @Description	Permanently closes an account. The balance must be exactly 0 first (withdraw or transfer out any remaining funds).
// @Tags			Account
// @Produce		json
// @Security		BearerAuth
// @Param			id	path	string	true	"The account ID"
// @Success		204	"The account was closed."
// @Failure		400	{object}	ErrorResponse	"One of: the account is already closed (`ACCOUNT_ALREADY_CLOSED`), or the balance is not 0 (`ACCOUNT_BALANCE_NOT_ZERO`)."
// @Failure		401	{object}	ErrorResponse	"The bearer token is missing, malformed, or invalid."
// @Failure		404	{object}	ErrorResponse	"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/close [post]
func (h *AccountHandler) CloseAccount(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	if err := h.closeAccount.Handle(r.Context(), command.CloseAccountCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
	}); err != nil {
		writeAccountError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// GetAccount looks up an account owned by the authenticated requester.
//
// @Summary		Look up an account
// @Description	Returns the account only if it belongs to the authenticated requester.
// @Tags			Account
// @Produce		json
// @Security		BearerAuth
// @Param			id	path		string				true	"The account ID"
// @Success		200	{object}	GetAccountResponse	"The account was found."
// @Failure		401	{object}	ErrorResponse		"The bearer token is missing, malformed, or invalid."
// @Failure		404	{object}	ErrorResponse		"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id} [get]
func (h *AccountHandler) GetAccount(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	result, err := h.getAccount.Handle(r.Context(), query.GetAccountQuery{
		AccountID:   accountID,
		RequesterID: requesterID,
	})
	if err != nil {
		writeAccountError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, r, GetAccountResponse{
		AccountID: result.AccountID,
		OwnerID:   result.OwnerID,
		Email:     result.Email,
		Balance:   MoneyResponse{Amount: result.Balance.Amount, Currency: result.Balance.Currency},
		Status:    result.Status,
		CreatedAt: result.CreatedAt,
		UpdatedAt: result.UpdatedAt,
	})
}

// GetTransactions lists an account's transaction history, paginated.
//
// @Summary		List an account's transaction history
// @Description	Returns the account's deposit/withdrawal/interest transactions, newest first, paginated with `page`/`take`. Out-of-range `page`/`take` values fall back to their defaults rather than failing.
// @Tags			Account
// @Produce		json
// @Security		BearerAuth
// @Param			id		path		string						true	"The account ID"
// @Param			page	query		int							false	"Page number, starting at 0"	default(0)
// @Param			take	query		int							false	"Page size"						default(20)
// @Success		200		{object}	GetTransactionsResponse		"The transaction history was found."
// @Failure		401		{object}	ErrorResponse				"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse				"No account exists with the given `id` for this requester (`ACCOUNT_NOT_FOUND`)."
// @Router			/accounts/{id}/transactions [get]
func (h *AccountHandler) GetTransactions(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	page, take := parsePagination(r)
	result, err := h.getTransactions.Handle(r.Context(), query.GetTransactionsQuery{
		AccountID:   accountID,
		RequesterID: requesterID,
		Page:        page,
		Take:        take,
	})
	if err != nil {
		writeAccountError(w, r, err)
		return
	}
	summaries := make([]TransactionSummaryResponse, len(result.Transactions))
	for i, t := range result.Transactions {
		summaries[i] = TransactionSummaryResponse{
			TransactionID: t.TransactionID,
			Type:          t.Type,
			Amount:        MoneyResponse{Amount: t.Amount.Amount, Currency: t.Amount.Currency},
			CreatedAt:     t.CreatedAt,
		}
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, r, GetTransactionsResponse{Transactions: summaries, Count: result.Count})
}

func parsePagination(r *http.Request) (page, take int) {
	page, take = 0, 20
	if v, err := strconv.Atoi(r.URL.Query().Get("page")); err == nil {
		page = v
	}
	if v, err := strconv.Atoi(r.URL.Query().Get("take")); err == nil {
		take = v
	}
	return page, take
}

// accountErrorMapping is the sentinel error → (HTTP status code,
// client-facing error code) mapping table. It's the Go idiom corresponding
// to the <Domain>ErrorCode enum in root docs/architecture/error-handling.md —
// a SCREAMING_SNAKE_CASE string sits alongside each sentinel error.
var accountErrorMapping = []struct {
	err    error
	status int
	code   string
}{
	{account.ErrNotFound, http.StatusNotFound, "ACCOUNT_NOT_FOUND"},
	{account.ErrInvalidAmount, http.StatusBadRequest, "ACCOUNT_INVALID_AMOUNT"},
	{account.ErrDepositRequiresActiveAccount, http.StatusBadRequest, "ACCOUNT_DEPOSIT_REQUIRES_ACTIVE_ACCOUNT"},
	{account.ErrWithdrawRequiresActiveAccount, http.StatusBadRequest, "ACCOUNT_WITHDRAW_REQUIRES_ACTIVE_ACCOUNT"},
	{account.ErrInsufficientBalance, http.StatusBadRequest, "ACCOUNT_INSUFFICIENT_BALANCE"},
	{account.ErrSuspendRequiresActiveAccount, http.StatusBadRequest, "ACCOUNT_SUSPEND_REQUIRES_ACTIVE_ACCOUNT"},
	{account.ErrReactivateRequiresSuspendedAccount, http.StatusBadRequest, "ACCOUNT_REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT"},
	{account.ErrAlreadyClosed, http.StatusBadRequest, "ACCOUNT_ALREADY_CLOSED"},
	{account.ErrBalanceNotZero, http.StatusBadRequest, "ACCOUNT_BALANCE_NOT_ZERO"},
	{account.ErrCurrencyMismatch, http.StatusBadRequest, "ACCOUNT_CURRENCY_MISMATCH"},
	{account.ErrTransferSameAccount, http.StatusBadRequest, "ACCOUNT_TRANSFER_SAME_ACCOUNT"},
}

func writeAccountError(w http.ResponseWriter, r *http.Request, err error) {
	for _, m := range accountErrorMapping {
		if errors.Is(err, m.err) {
			writeJSONError(w, r, m.status, m.code, err.Error())
			return
		}
	}
	slog.ErrorContext(r.Context(), "unhandled account error", "error", err)
	writeJSONError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error")
}

// writeValidationError responds with the standard JSON error schema for a
// request-shape/input-validation failure (a malformed body, a missing
// required field, an out-of-range value, ...) — the Go idiom corresponding
// to nestjs's global ValidationPipe's VALIDATION_FAILED response
// (error-handling.md). Every handler validates its own request body inline
// (this repository has no shared validation middleware/framework), so this
// helper only fixes the status/code, keeping every 400 response actually
// matching what's documented in the Swagger annotations above each handler.
func writeValidationError(w http.ResponseWriter, r *http.Request, message string) {
	writeJSONError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", message)
}

// writeJSONError writes the standard {statusCode, code, message, error} JSON
// error response required by root docs/architecture/error-handling.md.
func writeJSONError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	writeJSON(w, r, ErrorResponse{
		StatusCode: status,
		Code:       code,
		Message:    message,
		Error:      http.StatusText(status),
	})
}

// writeJSON encodes v as the response body. By this point the status
// code/headers have already been written, so there is no way to notify the
// client of an Encode failure — only a log is left.
func writeJSON(w http.ResponseWriter, r *http.Request, v any) {
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.ErrorContext(r.Context(), "failed to encode json response", "error", err)
	}
}
