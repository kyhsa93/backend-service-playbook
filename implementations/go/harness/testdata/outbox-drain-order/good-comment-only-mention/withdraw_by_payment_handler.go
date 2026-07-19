package command

// WithdrawByPaymentHandler does NOT reference OutboxRelay/outbox.Poller/outbox.Consumer —
// it only mentions those names in this explanatory comment. It is always invoked from
// outbox.Consumer's handlers map, so referencing them itself would be redundant.
type WithdrawByPaymentHandler struct {
	repo Repository
}

func (h *WithdrawByPaymentHandler) Handle() error {
	return h.repo.Save(ctx, a)
}
