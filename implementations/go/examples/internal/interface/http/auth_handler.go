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

// SignUp registers a new user credential.
//
// @Summary		Sign up
// @Description	Registers a new user ID with a password. The password is hashed before storage; the plaintext is never persisted.
// @Tags			Auth
// @Accept			json
// @Produce		json
// @Param			body	body	SignUpRequest	true	"New user ID and password"
// @Success		201		"The user was registered."
// @Failure		400		{object}	ErrorResponse	"One of: request validation failed (`VALIDATION_FAILED`) — e.g. a missing `userId` or a password under 8 characters — or the user ID is already taken (`USER_ID_ALREADY_EXISTS`)."
// @Router			/auth/sign-up [post]
func (h *AuthHandler) SignUp(w http.ResponseWriter, r *http.Request) {
	var body SignUpRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.UserID == "" {
		writeValidationError(w, r, "invalid request body")
		return
	}
	if len(body.Password) < minPasswordLength {
		writeValidationError(w, r, "password must be at least 8 characters")
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
//
// @Summary		Sign in
// @Description	Verifies the user ID/password and issues a JWT bearer token. Responds with the same error whether the user ID doesn't exist or the password is wrong, to prevent user enumeration.
// @Tags			Auth
// @Accept			json
// @Produce		json
// @Param			body	body		SignInRequest	true	"User ID and password"
// @Success		201		{object}	SignInResponse	"A bearer token was issued."
// @Failure		400		{object}	ErrorResponse	"Request validation failed (`VALIDATION_FAILED`) — e.g. a missing `userId`/`password`."
// @Failure		401		{object}	ErrorResponse	"The user ID doesn't exist, or the password doesn't match (`INVALID_CREDENTIALS`)."
// @Router			/auth/sign-in [post]
func (h *AuthHandler) SignIn(w http.ResponseWriter, r *http.Request) {
	var body SignInRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.UserID == "" || body.Password == "" {
		writeValidationError(w, r, "invalid request body")
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
