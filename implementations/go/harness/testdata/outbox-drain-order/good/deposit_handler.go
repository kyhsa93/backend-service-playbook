package command

type DepositHandler struct {
	repo        Repository
	outboxRelay OutboxRelay
}

func (h *DepositHandler) Handle() error {
	if err := h.repo.Save(ctx, a); err != nil {
		return err
	}
	if err := h.outboxRelay.ProcessPending(ctx); err != nil {
		return err
	}
	return nil
}
