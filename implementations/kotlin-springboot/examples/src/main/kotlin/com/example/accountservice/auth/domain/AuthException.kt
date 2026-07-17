package com.example.accountservice.auth.domain

sealed class AuthException(
    message: String,
    val code: AuthErrorCode,
) : RuntimeException(message)

// 아이디 미존재와 비밀번호 불일치를 동일한 예외/메시지로 응답한다 — 둘을 구분해서 응답하면
// 공격자가 존재하는 아이디를 추측할 수 있다(user enumeration).
class InvalidCredentialsException : AuthException("아이디 또는 비밀번호가 올바르지 않습니다.", AuthErrorCode.INVALID_CREDENTIALS)

class UserIdAlreadyExistsException : AuthException("이미 사용 중인 아이디입니다.", AuthErrorCode.USER_ID_ALREADY_EXISTS)
