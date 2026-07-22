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

// IssueCard issues a new card linked to an active account owned by the requester.
//
// @Summary		Issue a card
// @Description	Issues a new card linked to an active account owned by the authenticated requester.
// @Tags			Card
// @Accept			json
// @Produce		json
// @Security		BearerAuth
// @Param			body	body		IssueCardRequest	true	"Linked account and card brand"
// @Success		201		{object}	CardResponse		"The card was issued."
// @Failure		400		{object}	ErrorResponse		"One of: request validation failed (`VALIDATION_FAILED`), or the linked account is not active (`CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT`)."
// @Failure		401		{object}	ErrorResponse		"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse		"No account exists with the given `accountId` for this requester (`LINKED_ACCOUNT_NOT_FOUND`)."
// @Router			/cards [post]
func (h *CardHandler) IssueCard(w http.ResponseWriter, r *http.Request) {
	requesterID, _ := middleware.UserIDFromContext(r.Context())
	var body IssueCardRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeValidationError(w, r, "invalid request body")
		return
	}
	if body.AccountID == "" || body.Brand == "" {
		writeValidationError(w, r, "accountId and brand are required")
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

// GetCard looks up a card owned by the authenticated requester.
//
// @Summary		Look up a card
// @Description	Returns the card only if it belongs to the authenticated requester.
// @Tags			Card
// @Produce		json
// @Security		BearerAuth
// @Param			cardId	path		string			true	"The card ID"
// @Success		200		{object}	CardResponse	"The card was found."
// @Failure		401		{object}	ErrorResponse	"The bearer token is missing, malformed, or invalid."
// @Failure		404		{object}	ErrorResponse	"No card exists with the given `cardId` for this requester (`CARD_NOT_FOUND`)."
// @Router			/cards/{cardId} [get]
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

// cardErrorMapping is the sentinel error → (HTTP status code, client-facing
// error code) mapping table (the same idiom as accountErrorMapping in
// account_handler.go — error-handling.md).
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
