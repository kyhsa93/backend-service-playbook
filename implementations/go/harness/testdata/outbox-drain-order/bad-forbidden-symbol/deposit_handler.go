package command

type DepositHandler struct {
	repo        Repository
	outboxRelay OutboxRelay
}

func (h *DepositHandler) Handle() error {
	return h.repo.Save(ctx, a)
}
