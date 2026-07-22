package http

import "net/http"

// CreateAccount opens a new account for the authenticated requester.
//
// @Summary		Open a new account
// @Description	Opens a new account for the authenticated requester with a 0 balance in the given currency.
// @Tags			Account
// @Success		201	{object}	CreateAccountResponse	"The account was created."
// @Failure		400	{object}	ErrorResponse			"Request validation failed (`VALIDATION_FAILED`)."
// @Router			/accounts [post]
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
}
