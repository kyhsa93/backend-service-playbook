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

// GetPayments — 목록은 항상 인증된 요청자 자신의 결제 내역만 반환한다. 이 저장소의 어떤
// 엔드포인트도 클라이언트가 넘긴 ?ownerId= 같은 소유자 ID를 신뢰하지 않는다
// (query.GetPaymentsQuery의 주석 참고).
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

// RequestRefund — 환불 거부는 도메인 관점에서 유효한 상태 전이다(입력이 잘못된 것이
// 아니라 RefundEligibilityService가 Payment+Refund 두 Aggregate를 조율해 내린 결론).
// 따라서 거부돼도 4xx가 아니라 201 + status:REJECTED로 응답한다 — 승인/거부 모두 요청
// 자체는 성공적으로 "평가"되었다.
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

// paymentErrorMapping은 sentinel error → (HTTP 상태 코드, client-facing 에러 코드) 매핑
// 테이블이다(account_handler.go의 accountErrorMapping과 동일한 관용구 —
// error-handling.md). Payment/Refund 두 Aggregate가 반환할 수 있는 모든 sentinel error를
// 담는다 — 일부는 현재 REST 표면에서 도달하지 않는 방어적 코드지만(errors.go 주석 참고)
// 매핑 테이블에는 여전히 함께 둔다(account/card errorMapping과 동일하게 REST 도달 가능성이
// 아니라 Aggregate 불변식 커버리지에 맞춘다).
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
