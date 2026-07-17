package http

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/credential"
)

// minPasswordLength는 nestjs 구현(class-validator @MinLength(8))과 동일한 최소
// 비밀번호 길이다.
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

// SignIn은 저장된 해시와 비교해 비밀번호를 검증한 뒤에만 토큰을 발급한다 — 이전에는
// userId만으로 즉시 토큰을 발급해 다른 사용자를 사칭할 수 있었다(#188).
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

// authErrorMapping은 account_handler.go의 accountErrorMapping과 동일한 관용구다
// (error-handling.md). ErrUserIDAlreadyExists를 409가 아니라 400으로 매핑하는 것은
// 이 저장소의 기존 관용(ErrAlreadyClosed 등 "현재 상태와 충돌하는 요청"류를 전부
// 400으로 매핑)과 nestjs 구현(BadRequestException) 모두를 따른 것이다.
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
