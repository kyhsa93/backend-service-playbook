package command

// WithdrawByPaymentHandler does NOT depend on OutboxRelay — it only mentions the name in
// this explanatory comment. It is always invoked from inside an already-running
// Relay.ProcessPending loop, so calling ProcessPending itself would be redundant.
type WithdrawByPaymentHandler struct {
	repo Repository
}

func (h *WithdrawByPaymentHandler) Handle() error {
	return h.repo.Save(ctx, a)
}
