package http

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/credential"
)

// minPasswordLength is the same minimum password length as the nestjs
// implementation (class-validator @MinLength(8)).
const minPasswordLength = 8

type AuthHandler struct {
	signUp *command.SignUpHandler
	signIn *command.SignInHandler
}

func NewAuthHandler(signUp *command.SignUpHandler, signIn *command.SignInHandler) *AuthHandler {
	return &AuthHandler{signUp: signUp, signIn: signIn}
}

func (h *AuthHandler) SignUp(w http.ResponseWriter, r *http.Request) {
	var body SignUpRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.UserID == "" {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if len(body.Password) < minPasswordLength {
		writeJSONError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "password must be at least 8 characters")
		return
	}

	if err := h.signUp.Handle(r.Context(), command.SignUpCommand{UserID: body.UserID, Password: body.Password}); err != nil {
		writeAuthError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

// SignIn issues a token only after verifying the password against the
// stored hash.
func (h *AuthHandler) SignIn(w http.ResponseWriter, r *http.Request) {
	var body SignInRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.UserID == "" || body.Password == "" {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	accessToken, err := h.signIn.Handle(r.Context(), command.SignInCommand{UserID: body.UserID, Password: body.Password})
	if err != nil {
		writeAuthError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	writeJSON(w, r, SignInResponse{AccessToken: accessToken})
}

// authErrorMapping follows the same idiom as accountErrorMapping in
// account_handler.go (error-handling.md). Mapping ErrUserIDAlreadyExists to
// 400 rather than 409 follows both this repository's existing convention
// (mapping all "requests that conflict with the current state" errors, such
// as ErrAlreadyClosed, to 400) and the nestjs implementation
// (BadRequestException).
var authErrorMapping = []struct {
	err    error
	status int
	code   string
}{
	{credential.ErrInvalidCredentials, http.StatusUnauthorized, "INVALID_CREDENTIALS"},
	{credential.ErrUserIDAlreadyExists, http.StatusBadRequest, "USER_ID_ALREADY_EXISTS"},
}

func writeAuthError(w http.ResponseWriter, r *http.Request, err error) {
	for _, m := range authErrorMapping {
		if errors.Is(err, m.err) {
			writeJSONError(w, r, m.status, m.code, err.Error())
			return
		}
	}
	slog.ErrorContext(r.Context(), "unhandled auth error", "error", err)
	writeJSONError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error")
}
