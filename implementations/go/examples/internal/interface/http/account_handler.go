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

func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	var body CreateAccountRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if !isValidEmail(body.Email) {
		http.Error(w, "email must be a valid, non-empty email address", http.StatusBadRequest)
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

// isValidEmail은 표준 라이브러리만으로 처리 가능한 최소한의 형식 검증을 한다.
// 별도 의존성을 추가하지 않기 위한 의도적으로 단순한 체크다.
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

func (h *AccountHandler) Deposit(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	var body DepositRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
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

func (h *AccountHandler) Withdraw(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	var body WithdrawRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
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

func (h *AccountHandler) Transfer(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	accountID := r.PathValue("id")
	var body TransferRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
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

// accountErrorMapping은 sentinel error → (HTTP 상태 코드, client-facing 에러 코드) 매핑 테이블이다.
// root docs/architecture/error-handling.md의 <Domain>ErrorCode enum에 대응하는 Go 관용구 —
// SCREAMING_SNAKE_CASE 문자열을 sentinel error 옆에 나란히 둔다.
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

// writeJSONError는 root docs/architecture/error-handling.md가 요구하는
// {statusCode, code, message, error} 표준 JSON 에러 응답을 기록한다.
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

// writeJSON은 v를 응답 바디로 인코딩한다. 이 시점에는 이미 상태 코드/헤더가
// 기록된 뒤이므로 Encode 실패를 클라이언트에 다시 알릴 방법이 없다 — 로그만 남긴다.
func writeJSON(w http.ResponseWriter, r *http.Request, v any) {
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.ErrorContext(r.Context(), "failed to encode json response", "error", err)
	}
}
