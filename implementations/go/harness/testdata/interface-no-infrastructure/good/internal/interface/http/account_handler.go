package http

import "github.com/example/account-service/internal/application/command"

type AccountHandler struct {
	deposit *command.DepositHandler
}
