package http

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/account"
)

type AccountHandler struct {
	createAccount     *command.CreateAccountHandler
	deposit           *command.DepositHandler
	withdraw          *command.WithdrawHandler
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
		suspendAccount:    suspendAccount,
		reactivateAccount: reactivateAccount,
		closeAccount:      closeAccount,
		getAccount:        getAccount,
		getTransactions:   getTransactions,
	}
}

func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
	var body CreateAccountRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{
		RequesterID: requesterID,
		Currency:    body.Currency,
	})
	if err != nil {
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(CreateAccountResponse{
		AccountID: a.AccountID,
		OwnerID:   a.OwnerID,
		Balance:   MoneyResponse{Amount: a.Balance.Amount, Currency: a.Balance.Currency},
		Status:    string(a.Status),
		CreatedAt: a.CreatedAt,
	})
}

func (h *AccountHandler) Deposit(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
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
		writeAccountError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(TransactionResponse{
		TransactionID: tx.TransactionID,
		AccountID:     tx.AccountID,
		Type:          string(tx.Type),
		Amount:        MoneyResponse{Amount: tx.Amount.Amount, Currency: tx.Amount.Currency},
		CreatedAt:     tx.CreatedAt,
	})
}

func (h *AccountHandler) Withdraw(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
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
		writeAccountError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(TransactionResponse{
		TransactionID: tx.TransactionID,
		AccountID:     tx.AccountID,
		Type:          string(tx.Type),
		Amount:        MoneyResponse{Amount: tx.Amount.Amount, Currency: tx.Amount.Currency},
		CreatedAt:     tx.CreatedAt,
	})
}

func (h *AccountHandler) SuspendAccount(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
	accountID := r.PathValue("id")
	if err := h.suspendAccount.Handle(r.Context(), command.SuspendAccountCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
	}); err != nil {
		writeAccountError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *AccountHandler) ReactivateAccount(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
	accountID := r.PathValue("id")
	if err := h.reactivateAccount.Handle(r.Context(), command.ReactivateAccountCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
	}); err != nil {
		writeAccountError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *AccountHandler) CloseAccount(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
	accountID := r.PathValue("id")
	if err := h.closeAccount.Handle(r.Context(), command.CloseAccountCommand{
		AccountID:   accountID,
		RequesterID: requesterID,
	}); err != nil {
		writeAccountError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *AccountHandler) GetAccount(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
	accountID := r.PathValue("id")
	result, err := h.getAccount.Handle(r.Context(), query.GetAccountQuery{
		AccountID:   accountID,
		RequesterID: requesterID,
	})
	if err != nil {
		writeAccountError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(GetAccountResponse{
		AccountID: result.AccountID,
		OwnerID:   result.OwnerID,
		Balance:   MoneyResponse{Amount: result.Balance.Amount, Currency: result.Balance.Currency},
		Status:    result.Status,
		CreatedAt: result.CreatedAt,
		UpdatedAt: result.UpdatedAt,
	})
}

func (h *AccountHandler) GetTransactions(w http.ResponseWriter, r *http.Request) {
	requesterID := r.Header.Get("X-User-Id")
	accountID := r.PathValue("id")
	page, take := parsePagination(r)
	result, err := h.getTransactions.Handle(r.Context(), query.GetTransactionsQuery{
		AccountID:   accountID,
		RequesterID: requesterID,
		Page:        page,
		Take:        take,
	})
	if err != nil {
		writeAccountError(w, err)
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
	json.NewEncoder(w).Encode(GetTransactionsResponse{Transactions: summaries, Count: result.Count})
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

func writeAccountError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, account.ErrNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	case errors.Is(err, account.ErrInvalidAmount),
		errors.Is(err, account.ErrDepositRequiresActiveAccount),
		errors.Is(err, account.ErrWithdrawRequiresActiveAccount),
		errors.Is(err, account.ErrInsufficientBalance),
		errors.Is(err, account.ErrSuspendRequiresActiveAccount),
		errors.Is(err, account.ErrReactivateRequiresSuspendedAccount),
		errors.Is(err, account.ErrAlreadyClosed),
		errors.Is(err, account.ErrBalanceNotZero):
		http.Error(w, err.Error(), http.StatusBadRequest)
	default:
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}
}
