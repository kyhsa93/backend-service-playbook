package com.example.accountservice.auth.domain

sealed class AuthException(
    message: String,
    val code: AuthErrorCode,
) : RuntimeException(message)

// Responds with the same exception/message for a non-existent user ID and a password mismatch —
// distinguishing between the two would let an attacker guess which user IDs exist (user enumeration).
class InvalidCredentialsException : AuthException("The user ID or password is incorrect.", AuthErrorCode.INVALID_CREDENTIALS)

class UserIdAlreadyExistsException : AuthException("This user ID is already in use.", AuthErrorCode.USER_ID_ALREADY_EXISTS)
