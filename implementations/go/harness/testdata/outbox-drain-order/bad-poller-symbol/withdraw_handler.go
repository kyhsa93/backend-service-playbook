package command

type WithdrawHandler struct {
	repo   Repository
	poller *outbox.Poller
}

func (h *WithdrawHandler) Handle() error {
	if err := h.repo.Save(ctx, a); err != nil {
		return err
	}
	h.poller.Run(ctx)
	return nil
}
