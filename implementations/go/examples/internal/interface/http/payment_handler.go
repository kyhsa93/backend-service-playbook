package http

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/payment"
	"github.com/example/account-service/internal/interface/http/middleware"
)

type PaymentHandler struct {
	createPayment *command.CreatePaymentHandler
	cancelPayment *command.CancelPaymentHandler
	requestRefund *command.RequestRefundHandler
	getPayment    *query.GetPaymentHandler
	getPayments   *query.GetPaymentsHandler
	getRefunds    *query.GetRefundsHandler
}

func NewPaymentHandler(
	createPayment *command.CreatePaymentHandler,
	cancelPayment *command.CancelPaymentHandler,
	requestRefund *command.RequestRefundHandler,
	getPayment *query.GetPaymentHandler,
	getPayments *query.GetPaymentsHandler,
	getRefunds *query.GetRefundsHandler,
) *PaymentHandler {
	return &PaymentHandler{
		createPayment: createPayment,
		cancelPayment: cancelPayment,
		requestRefund: requestRefund,
		getPayment:    getPayment,
		getPayments:   getPayments,
		getRefunds:    getRefunds,
	}
}

func (h *PaymentHandler) CreatePayment(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	var body CreatePaymentRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if body.CardID == "" || body.Amount < 1 {
		http.Error(w, "cardId is required and amount must be a positive integer", http.StatusBadRequest)
		return
	}
	p, err := h.createPayment.Handle(r.Context(), command.CreatePaymentCommand{
		CardID:      body.CardID,
		Amount:      body.Amount,
		RequesterID: requesterID,
	})
	if err != nil {
		writePaymentError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, toPaymentResponse(p.PaymentID, p.CardID, p.AccountID, p.OwnerID, p.Amount, string(p.Status), p.CreatedAt))
}

func (h *PaymentHandler) CancelPayment(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	paymentID := r.PathValue("paymentId")
	var body CancelPaymentRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if _, err := h.cancelPayment.Handle(r.Context(), command.CancelPaymentCommand{
		PaymentID:   paymentID,
		Reason:      body.Reason,
		RequesterID: requesterID,
	}); err != nil {
		writePaymentError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *PaymentHandler) GetPayment(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	paymentID := r.PathValue("paymentId")
	result, err := h.getPayment.Handle(r.Context(), query.GetPaymentQuery{
		PaymentID:   paymentID,
		RequesterID: requesterID,
	})
	if err != nil {
		writePaymentError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, r, toPaymentResponse(result.PaymentID, result.CardID, result.AccountID, result.OwnerID, result.Amount, result.Status, result.CreatedAt))
}

// GetPayments — the list always returns only the authenticated requester's
// own payment history. No endpoint in this repository trusts an owner ID
// passed by the client, such as a ?ownerId= query param (see the comment on
// query.GetPaymentsQuery).
func (h *PaymentHandler) GetPayments(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	page, take := parsePagination(r)
	result, err := h.getPayments.Handle(r.Context(), query.GetPaymentsQuery{
		RequesterID: requesterID,
		Page:        page,
		Take:        take,
	})
	if err != nil {
		writePaymentError(w, r, err)
		return
	}
	payments := make([]PaymentResponse, len(result.Payments))
	for i, p := range result.Payments {
		payments[i] = toPaymentResponse(p.PaymentID, p.CardID, p.AccountID, p.OwnerID, p.Amount, p.Status, p.CreatedAt)
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, r, GetPaymentsResponse{Payments: payments, Count: result.Count})
}

// RequestRefund — a refund rejection is a valid state transition from a
// domain perspective (not invalid input, but a conclusion reached by
// RefundEligibilityService coordinating the Payment and Refund Aggregates).
// So even on rejection, it responds with 201 + status:REJECTED rather than a
// 4xx — whether approved or rejected, the request itself was successfully
// "evaluated."
func (h *PaymentHandler) RequestRefund(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	paymentID := r.PathValue("paymentId")
	var body RequestRefundRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if body.Amount < 1 || body.Reason == "" {
		http.Error(w, "amount must be a positive integer and reason is required", http.StatusBadRequest)
		return
	}
	ref, err := h.requestRefund.Handle(r.Context(), command.RequestRefundCommand{
		PaymentID:   paymentID,
		Amount:      body.Amount,
		Reason:      body.Reason,
		RequesterID: requesterID,
	})
	if err != nil {
		writePaymentError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, toRefundResponse(ref.RefundID, ref.PaymentID, ref.Amount, ref.Reason, string(ref.Status), ref.DecisionNote, ref.CreatedAt))
}

func (h *PaymentHandler) GetRefunds(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	paymentID := r.PathValue("paymentId")
	page, take := parsePagination(r)
	result, err := h.getRefunds.Handle(r.Context(), query.GetRefundsQuery{
		PaymentID:   paymentID,
		RequesterID: requesterID,
		Page:        page,
		Take:        take,
	})
	if err != nil {
		writePaymentError(w, r, err)
		return
	}
	refunds := make([]RefundResponse, len(result.Refunds))
	for i, ref := range result.Refunds {
		refunds[i] = toRefundResponse(ref.RefundID, ref.PaymentID, ref.Amount, ref.Reason, ref.Status, ref.DecisionNote, ref.CreatedAt)
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, r, GetRefundsResponse{Refunds: refunds, Count: result.Count})
}

func toPaymentResponse(paymentID, cardID, accountID, ownerID string, amount int64, status string, createdAt time.Time) PaymentResponse {
	return PaymentResponse{
		PaymentID: paymentID,
		CardID:    cardID,
		AccountID: accountID,
		OwnerID:   ownerID,
		Amount:    amount,
		Status:    status,
		CreatedAt: createdAt,
	}
}

func toRefundResponse(refundID, paymentID string, amount int64, reason, status, decisionNote string, createdAt time.Time) RefundResponse {
	return RefundResponse{
		RefundID:     refundID,
		PaymentID:    paymentID,
		Amount:       amount,
		Reason:       reason,
		Status:       status,
		DecisionNote: decisionNote,
		CreatedAt:    createdAt,
	}
}

// paymentErrorMapping is the sentinel error → (HTTP status code,
// client-facing error code) mapping table (the same idiom as
// accountErrorMapping in account_handler.go — error-handling.md). It holds
// every sentinel error the Payment/Refund Aggregates can return — some are
// currently unreachable defensive code from the REST surface (see the
// comment in errors.go), but they still belong in the mapping table (as with
// the account/card errorMapping, this is scoped to Aggregate invariant
// coverage, not REST reachability).
var paymentErrorMapping = []struct {
	err    error
	status int
	code   string
}{
	{payment.ErrNotFound, http.StatusNotFound, "PAYMENT_NOT_FOUND"},
	{payment.ErrLinkedCardNotFound, http.StatusNotFound, "LINKED_CARD_NOT_FOUND"},
	{payment.ErrRequiresActiveCard, http.StatusBadRequest, "PAYMENT_REQUIRES_ACTIVE_CARD"},
	{payment.ErrLinkedAccountNotFound, http.StatusNotFound, "LINKED_ACCOUNT_NOT_FOUND"},
	{payment.ErrRequiresActiveAccount, http.StatusBadRequest, "PAYMENT_REQUIRES_ACTIVE_ACCOUNT"},
	{payment.ErrInsufficientBalance, http.StatusBadRequest, "INSUFFICIENT_BALANCE"},
	{payment.ErrCancelRequiresCompletedPayment, http.StatusBadRequest, "PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT"},
	{payment.ErrRefundRequiresCompletedPayment, http.StatusBadRequest, "REFUND_REQUIRES_COMPLETED_PAYMENT"},
	{payment.ErrRefundAmountExceedsPayment, http.StatusBadRequest, "REFUND_AMOUNT_EXCEEDS_PAYMENT"},
	{payment.ErrCompleteRequiresPendingPayment, http.StatusBadRequest, "PAYMENT_COMPLETE_REQUIRES_PENDING_PAYMENT"},
	{payment.ErrFailRequiresPendingPayment, http.StatusBadRequest, "PAYMENT_FAIL_REQUIRES_PENDING_PAYMENT"},
	{payment.ErrRefundApproveRequiresRequestedRefund, http.StatusBadRequest, "REFUND_APPROVE_REQUIRES_REQUESTED_REFUND"},
	{payment.ErrRefundRejectRequiresRequestedRefund, http.StatusBadRequest, "REFUND_REJECT_REQUIRES_REQUESTED_REFUND"},
	{payment.ErrRefundCompleteRequiresApprovedRefund, http.StatusBadRequest, "REFUND_COMPLETE_REQUIRES_APPROVED_REFUND"},
}

func writePaymentError(w http.ResponseWriter, r *http.Request, err error) {
	for _, m := range paymentErrorMapping {
		if errors.Is(err, m.err) {
			writeJSONError(w, r, m.status, m.code, err.Error())
			return
		}
	}
	slog.ErrorContext(r.Context(), "unhandled payment error", "error", err)
	writeJSONError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error")
}
