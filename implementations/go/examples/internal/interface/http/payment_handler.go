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

// CreatePayment charges an active card linked to an active account with sufficient balance.
//
// @Summary		Create a payment
// @Description	Charges an active card linked to an active account with sufficient balance. The account balance is debited asynchronously once the payment completes.
// @Tags			Payment
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			body	body		CreatePaymentRequest	true	"Card to charge and amount"
// @Success		201		{object}	PaymentResponse			"The payment was created."
// @Failure		400		{object}	ErrorResponse			"One of: request validation failed (`VALIDATION_FAILED`), the card is not active (`PAYMENT_REQUIRES_ACTIVE_CARD`), the linked account is not active (`PAYMENT_REQUIRES_ACTIVE_ACCOUNT`), or the account balance is insufficient (`INSUFFICIENT_BALANCE`)."
// @Failure		401		{object}	ErrorResponse			"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse			"One of: no card exists with the given `cardId` (`LINKED_CARD_NOT_FOUND`), or the card's linked account could not be found (`LINKED_ACCOUNT_NOT_FOUND`)."
// @Router			/payments [post]
func (h *PaymentHandler) CreatePayment(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	var body CreatePaymentRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
		return
	}
	if body.CardID == "" || body.Amount < 1 {
		writeValidationError(w, r, "cardId is required and amount must be a positive integer")
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

// CancelPayment cancels a completed payment before it is refunded.
//
// @Summary		Cancel a payment
// @Description	Cancels a completed payment before it is refunded.
// @Tags			Payment
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			paymentId	path	string					true	"The payment ID"
// @Param			body		body	CancelPaymentRequest	true	"Cancellation reason"
// @Success		204			"The payment was cancelled."
// @Failure		400			{object}	ErrorResponse	"One of: request validation failed (`VALIDATION_FAILED`), or only a completed payment can be cancelled (`PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT`)."
// @Failure		401			{object}	ErrorResponse	"The bearer token is missing, malformed, or invalid."
// @Failure		404			{object}	ErrorResponse	"No payment exists with the given `paymentId` (`PAYMENT_NOT_FOUND`)."
// @Router			/payments/{paymentId}/cancel [post]
func (h *PaymentHandler) CancelPayment(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	paymentID := r.PathValue("paymentId")
	var body CancelPaymentRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
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

// GetPayment looks up a payment owned by the authenticated requester.
//
// @Summary		Look up a payment
// @Description	Returns the payment only if it belongs to the authenticated requester.
// @Tags			Payment
// @Produce		json
// @Security		BearerAuth
// @Param			paymentId	path		string			true	"The payment ID"
// @Success		200			{object}	PaymentResponse	"The payment was found."
// @Failure		401			{object}	ErrorResponse	"The bearer token is missing, malformed, or invalid."
// @Failure		404			{object}	ErrorResponse	"No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`)."
// @Router			/payments/{paymentId} [get]
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
//
// @Summary		List the requester's payments
// @Description	Returns the authenticated requester's payments, newest first, paginated with `page`/`take`. Out-of-range `page`/`take` values fall back to their defaults rather than failing.
// @Tags			Payment
// @Produce		json
// @Security		BearerAuth
// @Param			page	query		int					false	"Page number, starting at 0"	default(0)
// @Param			take	query		int					false	"Page size"						default(20)
// @Success		200		{object}	GetPaymentsResponse	"The payment list was found."
// @Failure		401		{object}	ErrorResponse		"The bearer token is missing, malformed, or invalid."
// @Router			/payments [get]
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
//
// @Summary		Request a refund
// @Description	Requests a refund for a payment. Eligibility is judged synchronously (`APPROVED`/`REJECTED`); an approved refund is credited back to the account asynchronously.
// @Tags			Payment
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			paymentId	path		string					true	"The payment ID"
// @Param			body		body		RequestRefundRequest	true	"Refund amount and reason"
// @Success		201			{object}	RefundResponse			"The refund request was recorded (its `status` may still be `REJECTED` — check the response body, not just the HTTP status)."
// @Failure		400			{object}	ErrorResponse			"Request validation failed (`VALIDATION_FAILED`) — e.g. a non-positive amount or missing reason."
// @Failure		401			{object}	ErrorResponse			"The bearer token is missing, malformed, or invalid."
// @Failure		404			{object}	ErrorResponse			"No payment exists with the given `paymentId` (`PAYMENT_NOT_FOUND`)."
// @Router			/payments/{paymentId}/refunds [post]
func (h *PaymentHandler) RequestRefund(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	paymentID := r.PathValue("paymentId")
	var body RequestRefundRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
		return
	}
	if body.Amount < 1 || body.Reason == "" {
		writeValidationError(w, r, "amount must be a positive integer and reason is required")
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

// GetRefunds lists the refunds requested against a payment, paginated.
//
// @Summary		List a payment's refunds
// @Description	Returns the refunds requested against a payment, newest first, paginated with `page`/`take`. Out-of-range `page`/`take` values fall back to their defaults rather than failing.
// @Tags			Payment
// @Produce		json
// @Security		BearerAuth
// @Param			paymentId	path		string				true	"The payment ID"
// @Param			page		query		int					false	"Page number, starting at 0"	default(0)
// @Param			take		query		int					false	"Page size"						default(20)
// @Success		200			{object}	GetRefundsResponse	"The refund list was found."
// @Failure		401			{object}	ErrorResponse		"The bearer token is missing, malformed, or invalid."
// @Failure		404			{object}	ErrorResponse		"No payment exists with the given `paymentId` (`PAYMENT_NOT_FOUND`)."
// @Router			/payments/{paymentId}/refunds [get]
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
