package test

import (
	"net/http"
	"testing"

	"github.com/stretchr/testify/require"
)

// signUp은 회원가입 요청을 보내고 응답을 반환한다(로그인 없이 호출하므로 userID는 "").
func signUp(t *testing.T, userID, password string) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/auth/sign-up", "", map[string]string{"userId": userID, "password": password})
}

// signIn은 로그인 요청을 보내고 응답을 반환한다.
func signIn(t *testing.T, userID, password string) *http.Response {
	t.Helper()
	return doRequest(t, http.MethodPost, "/auth/sign-in", "", map[string]string{"userId": userID, "password": password})
}

// TestSignUp은 회원가입 유스케이스(#188 수정으로 새로 추가됨)를 검증한다.
func TestSignUp(t *testing.T) {
	t.Run("신규_아이디로_가입하면_201을_반환한다", func(t *testing.T) {
		resp := signUp(t, "auth-signup-1", "password123!")
		require.Equal(t, http.StatusCreated, resp.StatusCode)
	})

	t.Run("이미_사용_중인_아이디면_400과_USER_ID_ALREADY_EXISTS를_반환한다", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signup-dup", "password123!").StatusCode)

		resp := signUp(t, "auth-signup-dup", "another-password!")
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "USER_ID_ALREADY_EXISTS", body["code"])
	})

	t.Run("비밀번호가_8자_미만이면_400과_VALIDATION_FAILED를_반환한다", func(t *testing.T) {
		resp := signUp(t, "auth-signup-short-pw", "short")
		require.Equal(t, http.StatusBadRequest, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "VALIDATION_FAILED", body["code"])
	})
}

// TestSignIn은 로그인 유스케이스를 검증한다 — sign-in이 실제 비밀번호 해시를
// 검증하는지, 그리고 아이디 미존재/비밀번호 불일치가 동일한 응답(401
// INVALID_CREDENTIALS)으로 합류하는지가 이 취약점 수정(#188)의 핵심이다.
func TestSignIn(t *testing.T) {
	t.Run("가입한_아이디와_비밀번호가_일치하면_201과_액세스_토큰을_반환한다", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signin-ok", "password123!").StatusCode)

		resp := signIn(t, "auth-signin-ok", "password123!")
		require.Equal(t, http.StatusCreated, resp.StatusCode)
		body := decodeBody(t, resp)
		require.NotEmpty(t, body["accessToken"])
	})

	t.Run("비밀번호가_틀리면_401과_INVALID_CREDENTIALS를_반환한다", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signin-wrong-pw", "password123!").StatusCode)

		resp := signIn(t, "auth-signin-wrong-pw", "wrong-password!")
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "INVALID_CREDENTIALS", body["code"])
	})

	t.Run("존재하지_않는_아이디면_401과_INVALID_CREDENTIALS를_반환한다", func(t *testing.T) {
		resp := signIn(t, "auth-signin-no-such-user", "password123!")
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode)
		body := decodeBody(t, resp)
		require.Equal(t, "INVALID_CREDENTIALS", body["code"])
	})

	t.Run("아이디_미존재와_비밀번호_불일치는_동일한_메시지를_반환한다", func(t *testing.T) {
		require.Equal(t, http.StatusCreated, signUp(t, "auth-signin-enum-check", "password123!").StatusCode)

		wrongPassword := decodeBody(t, signIn(t, "auth-signin-enum-check", "wrong-password!"))
		noSuchUser := decodeBody(t, signIn(t, "auth-signin-enum-no-such-user", "password123!"))

		// user enumeration 방지 — 두 실패 사유가 응답만으로는 구분되지 않아야 한다.
		require.Equal(t, wrongPassword["code"], noSuchUser["code"])
		require.Equal(t, wrongPassword["message"], noSuchUser["message"])
	})
}
