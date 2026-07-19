package command

import "somepkg/internal/infrastructure/outbox"

type DepositHandler struct {
	writer *outbox.Writer
}
