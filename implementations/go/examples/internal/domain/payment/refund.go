package payment

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// RefundReasonCategory is the fixed taxonomy RefundReasonClassifier (a
// Technical Service, see internal/application/event/refund_reason_classifier.go)
// classifies a refund's free-text Reason into, for ops-analytics reporting
// only (see application/query/get_refund_reason_insights_handler.go) — it
// never feeds back into EvaluateRefundEligibility's approve/reject judgment.
type RefundReasonCategory string

const (
	RefundReasonCategoryDefectiveProduct RefundReasonCategory = "DEFECTIVE_PRODUCT"
	RefundReasonCategoryWrongItem        RefundReasonCategory = "WRONG_ITEM"
	RefundReasonCategoryNotAsDescribed   RefundReasonCategory = "NOT_AS_DESCRIBED"
	RefundReasonCategoryChangedMind      RefundReasonCategory = "CHANGED_MIND"
	RefundReasonCategoryLateDelivery     RefundReasonCategory = "LATE_DELIVERY"
	RefundReasonCategoryDuplicateCharge  RefundReasonCategory = "DUPLICATE_CHARGE"
	RefundReasonCategoryOther            RefundReasonCategory = "OTHER"
)

// Refund is a separate Aggregate Root representing a refund. Refund itself
// cannot judge the original payment's (Payment) status or amount —
// Approve()/Reject() are only called after receiving the result
// (RefundDecision) of EvaluateRefundEligibility (a Domain Service, in this
// package's refund_eligibility_service.go), which loads and coordinates
// both the Payment and Refund Aggregates together.
type Refund struct {
	RefundID       string
	PaymentID      string
	Amount         int64
	Reason         string
	Status         RefundStatus
	DecisionNote   string
	ReasonCategory RefundReasonCategory
	CreatedAt      time.Time
	events         []DomainEvent
}

// NewRefund is a pure creation factory that makes a refund request in
// REQUESTED status. It unconditionally raises RefundRequested — before the
// caller (RequestRefundHandler) even runs EvaluateRefundEligibility's
// approve/reject judgment — since ClassifyRefundReasonEventHandler's
// ops-analytics classification must happen (and be reported on) identically
// regardless of that judgment's outcome.
func NewRefund(paymentID string, amount int64, reason string) *Refund {
	r := &Refund{
		RefundID:  common.NewID(),
		PaymentID: paymentID,
		Amount:    amount,
		Reason:    reason,
		Status:    RefundStatusRequested,
		CreatedAt: common.Now(),
	}
	r.events = append(r.events, RefundRequested{
		RefundID:  r.RefundID,
		PaymentID: r.PaymentID,
		Reason:    r.Reason,
		CreatedAt: r.CreatedAt,
	})
	return r
}

// ReconstituteRefund restores a row read from storage into a domain object.
func ReconstituteRefund(refundID, paymentID string, amount int64, reason string, status RefundStatus, decisionNote string, reasonCategory RefundReasonCategory, createdAt time.Time) *Refund {
	return &Refund{
		RefundID:       refundID,
		PaymentID:      paymentID,
		Amount:         amount,
		Reason:         reason,
		Status:         status,
		DecisionNote:   decisionNote,
		ReasonCategory: reasonCategory,
		CreatedAt:      createdAt,
	}
}

// Approve approves a refund. paymentContext (accountID/ownerID) is not a
// judgment result from EvaluateRefundEligibility — it's just reference data
// passed in from the Payment that the caller (RequestRefundHandler) has
// already loaded, used only to assemble the Integration Event to propagate
// to an external BC after the judgment — it is not promoted to a field of
// Refund itself.
func (r *Refund) Approve(accountID, ownerID string) error {
	if r.Status != RefundStatusRequested {
		return ErrRefundApproveRequiresRequestedRefund
	}
	r.Status = RefundStatusApproved
	r.DecisionNote = "refund approved"
	r.events = append(r.events, RefundApproved{
		RefundID:   r.RefundID,
		PaymentID:  r.PaymentID,
		AccountID:  accountID,
		OwnerID:    ownerID,
		Amount:     r.Amount,
		ApprovedAt: common.Now(),
	})
	return nil
}

// Reject rejects a refund. RequestRefundHandler does not treat this return
// value as an error — it saves the Refund in REJECTED status and returns it
// as-is (a refund rejection is a valid domain state transition, not an
// input error) — no Command treats this method as an error.
func (r *Refund) Reject(reason string) error {
	if r.Status != RefundStatusRequested {
		return ErrRefundRejectRequiresRequestedRefund
	}
	r.Status = RefundStatusRejected
	r.DecisionNote = reason
	return nil
}

// Complete marks a refund as completed. Currently, refund processing ends
// once the Account BC subscribes to refund.approved.v1 and executes the
// credit, and there is no callback path that reports that credit's success
// back to the Payment BC (not present on the REST surface) — it's
// unconnected for the same reason as Payment.Fail(). It's kept for the
// completeness of the state model and verified only with Domain unit
// tests.
func (r *Refund) Complete() error {
	if r.Status != RefundStatusApproved {
		return ErrRefundCompleteRequiresApprovedRefund
	}
	r.Status = RefundStatusCompleted
	return nil
}

// CategorizeReason is set asynchronously by ClassifyRefundReasonEventHandler
// reacting to RefundRequested — never called from the Approve()/Reject()
// eligibility path. It raises no further Domain Event: this is a read-model
// enrichment, not something any other BC needs to react to.
func (r *Refund) CategorizeReason(category RefundReasonCategory) {
	r.ReasonCategory = category
}

func (r *Refund) DomainEvents() []DomainEvent { return r.events }
func (r *Refund) ClearEvents()                { r.events = nil }
