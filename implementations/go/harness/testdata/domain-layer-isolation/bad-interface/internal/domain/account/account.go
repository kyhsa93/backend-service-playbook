package account

import "github.com/example/account-service/internal/interface/http"

type Account struct {
	AccountID string
	dto       http.AccountResponse
}
