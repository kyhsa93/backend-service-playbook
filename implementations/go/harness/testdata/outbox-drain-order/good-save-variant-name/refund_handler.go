package command

type RequestRefundHandler struct {
	refunds     RefundRepository
	outboxRelay OutboxRelay
}

func (h *RequestRefundHandler) Handle() error {
	if err := h.refunds.SaveRefund(ctx, r); err != nil {
		return err
	}
	if err := h.outboxRelay.ProcessPending(ctx); err != nil {
		return err
	}
	return nil
}
