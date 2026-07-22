package http

import "net/http"

// GetAccount looks up an account — documents only the success response, a violation case.
//
// @Summary		Look up an account
// @Description	Returns the account only if it belongs to the authenticated requester.
// @Tags			Account
// @Success		200	{object}	GetAccountResponse	"The account was found."
// @Router			/accounts/{id} [get]
func (h *AccountHandler) GetAccount(w http.ResponseWriter, r *http.Request) {
}
