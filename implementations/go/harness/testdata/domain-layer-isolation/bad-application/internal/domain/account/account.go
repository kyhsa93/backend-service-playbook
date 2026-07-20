package account

import "github.com/example/account-service/internal/application/command"

type Account struct {
	AccountID string
	cmd       command.DepositCommand
}
