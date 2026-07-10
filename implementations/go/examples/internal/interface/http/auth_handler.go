package http

import (
	"encoding/json"
	"net/http"

	"github.com/example/account-service/internal/infrastructure/auth"
)

type SignInRequest struct {
	UserID string `json:"userId"`
}

type SignInResponse struct {
	AccessToken string `json:"accessToken"`
}

type AuthHandler struct {
	jwtService *auth.JWTService
}

func NewAuthHandler(jwtService *auth.JWTService) *AuthHandler {
	return &AuthHandler{jwtService: jwtService}
}

func (h *AuthHandler) SignIn(w http.ResponseWriter, r *http.Request) {
	var body SignInRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.UserID == "" {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	accessToken, err := h.jwtService.Sign(body.UserID)
	if err != nil {
		http.Error(w, "failed to sign token", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(SignInResponse{AccessToken: accessToken})
}
