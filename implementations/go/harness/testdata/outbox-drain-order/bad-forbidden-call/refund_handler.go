package command

type RequestRefundHandler struct {
	refunds RefundRepository
	outbox  OutboxRelay
}

func (h *RequestRefundHandler) Handle() error {
	if err := h.refunds.SaveRefund(ctx, r); err != nil {
		return err
	}
	if err := h.outbox.ProcessPending(ctx); err != nil {
		return err
	}
	return nil
}
