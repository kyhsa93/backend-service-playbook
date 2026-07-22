package test

import (
	"net/http"
	"testing"

	"github.com/stretchr/testify/require"
)

// signUp sends a sign-up request and returns the response (called without
// being logged in, so userID is "").
func signUp(t *testing.T, userID, password string) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/auth/sign-up", "", map[string]string{"userId": userID, "password": password})
}

// signIn sends a sign-in request and returns the response.
func signIn(t *testing.T, userID, password string) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/auth/sign-in", "", map[string]string{"userId": userID, "password": password})
}

// TestSignUp verifies the sign-up use case.
func TestSignUp(t *testing.T) {
	t.Run("signing_up_with_a_new_ID_returns_201", func(t *testing.T) {
		resp := signUp(t, "auth-signup-1", "password123!")
		require.Equal(t, http.StatusCreated, resp.StatusCode)
	})

	t.Run("an_already_used_ID_returns_400_and_USER_ID_ALREADY_EXISTS", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signup-dup", "password123!").StatusCode)

		resp := signUp(t, "auth-signup-dup", "another-password!")
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "USER_ID_ALREADY_EXISTS", body["code"])
	})

	t.Run("a_password_under_8_characters_returns_400_and_VALIDATION_FAILED", func(t *testing.T) {
		resp := signUp(t, "auth-signup-short-pw", "short")
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "VALIDATION_FAILED", body["code"])
	})
}

// TestSignIn verifies the sign-in use case — it confirms that sign-in
// actually verifies the password hash, and that a nonexistent user ID and a
// password mismatch converge on the same response (401
// INVALID_CREDENTIALS) (preventing user enumeration).
func TestSignIn(t *testing.T) {
	t.Run("a_matching_registered_ID_and_password_returns_201_and_an_access_token", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signin-ok", "password123!").StatusCode)

		resp := signIn(t, "auth-signin-ok", "password123!")
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.NotEmpty(t, body["accessToken"])
	})

	t.Run("a_wrong_password_returns_401_and_INVALID_CREDENTIALS", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signin-wrong-pw", "password123!").StatusCode)

		resp := signIn(t, "auth-signin-wrong-pw", "wrong-password!")
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "INVALID_CREDENTIALS", body["code"])
	})

	t.Run("a_nonexistent_ID_returns_401_and_INVALID_CREDENTIALS", func(t *testing.T) {
		resp := signIn(t, "auth-signin-no-such-user", "password123!")
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "INVALID_CREDENTIALS", body["code"])
	})

	t.Run("nonexistent_ID_and_wrong_password_return_the_same_message", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signin-enum-check", "password123!").StatusCode)

		wrongPassword := decodeBody(t, signIn(t, "auth-signin-enum-check", "wrong-password!"))
		noSuchUser := decodeBody(t, signIn(t, "auth-signin-enum-no-such-user", "password123!"))

		// Prevents user enumeration — the two failure reasons must be
		// indistinguishable from the response alone.
		require.Equal(t, wrongPassword["code"], noSuchUser["code"])
		require.Equal(t, wrongPassword["message"], noSuchUser["message"])
	})
}
