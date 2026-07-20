package account

import "github.com/example/account-service/internal/infrastructure/persistence"

type Account struct {
	AccountID string
	tx        persistence.Tx
}
