package http

import "net/http"

// GetAccount looks up an account — missing @Summary/@Description, a violation case.
//
// @Tags	Account
// @Success	200	{object}	GetAccountResponse	"The account was found."
// @Failure	404	{object}	ErrorResponse		"No account exists (`ACCOUNT_NOT_FOUND`)."
// @Router	/accounts/{id} [get]
func (h *AccountHandler) GetAccount(w http.ResponseWriter, r *http.Request) {
}
