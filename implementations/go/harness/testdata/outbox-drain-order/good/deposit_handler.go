package command

type DepositHandler struct {
	repo Repository
}

func (h *DepositHandler) Handle() error {
	if err := h.repo.Save(ctx, a); err != nil {
		return err
	}
	return nil
}
