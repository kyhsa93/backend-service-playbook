package http

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/card"
	"github.com/example/account-service/internal/interface/http/middleware"
)

type CardHandler struct {
	issueCard *command.IssueCardHandler
	getCard   *query.GetCardHandler
}

func NewCardHandler(issueCard *command.IssueCardHandler, getCard *query.GetCardHandler) *CardHandler {
	return &CardHandler{issueCard: issueCard, getCard: getCard}
}

func (h *CardHandler) IssueCard(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	var body IssueCardRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if body.AccountID == "" || body.Brand == "" {
		http.Error(w, "accountId and brand are required", http.StatusBadRequest)
		return
	}
	c, err := h.issueCard.Handle(r.Context(), command.IssueCardCommand{
		AccountID:   body.AccountID,
		Brand:       body.Brand,
		RequesterID: requesterID,
	})
	if err != nil {
		writeCardError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, CardResponse{
		CardID:    c.CardID,
		AccountID: c.AccountID,
		OwnerID:   c.OwnerID,
		Brand:     c.Brand,
		Status:    string(c.Status),
		CreatedAt: c.CreatedAt,
	})
}

func (h *CardHandler) GetCard(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	cardID := r.PathValue("cardId")
	result, err := h.getCard.Handle(r.Context(), query.GetCardQuery{
		CardID:      cardID,
		RequesterID: requesterID,
	})
	if err != nil {
		writeCardError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	writeJSON(w, r, CardResponse{
		CardID:    result.CardID,
		AccountID: result.AccountID,
		OwnerID:   result.OwnerID,
		Brand:     result.Brand,
		Status:    result.Status,
		CreatedAt: result.CreatedAt,
	})
}

// cardErrorMapping은 sentinel error → (HTTP 상태 코드, client-facing 에러 코드) 매핑 테이블이다
// (account_handler.go의 accountErrorMapping과 동일한 관용구 — error-handling.md).
var cardErrorMapping = []struct {
	err    error
	status int
	code   string
}{
	{card.ErrNotFound, http.StatusNotFound, "CARD_NOT_FOUND"},
	{card.ErrLinkedAccountNotFound, http.StatusNotFound, "LINKED_ACCOUNT_NOT_FOUND"},
	{card.ErrIssueRequiresActiveAccount, http.StatusBadRequest, "CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT"},
}

func writeCardError(w http.ResponseWriter, r *http.Request, err error) {
	for _, m := range cardErrorMapping {
		if errors.Is(err, m.err) {
			writeJSONError(w, r, m.status, m.code, err.Error())
			return
		}
	}
	slog.ErrorContext(r.Context(), "unhandled card error", "error", err)
	writeJSONError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error")
}
