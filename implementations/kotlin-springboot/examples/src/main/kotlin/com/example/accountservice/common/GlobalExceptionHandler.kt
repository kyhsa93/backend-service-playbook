package com.example.accountservice.common

import com.example.accountservice.account.domain.AccountException
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.interfaces.rest.ErrorResponse
import com.example.accountservice.auth.domain.AuthException
import com.example.accountservice.auth.domain.InvalidCredentialsException
import com.example.accountservice.card.domain.CardException
import com.example.accountservice.card.domain.CardNotFoundException
import com.example.accountservice.card.domain.LinkedAccountNotFoundException
import com.example.accountservice.payment.domain.LinkedCardNotFoundException
import com.example.accountservice.payment.domain.PaymentException
import com.example.accountservice.payment.domain.PaymentNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import com.example.accountservice.payment.domain.LinkedAccountNotFoundException as PaymentLinkedAccountNotFoundException

/**
 * Converts application-wide exceptions into HTTP responses.
 *
 * Rather than scattering `@ExceptionHandler`s across every Controller, they are all gathered in this
 * one class — adding a new Controller just reuses this handler as-is. Thanks to the
 * `sealed class AccountException` hierarchy, catching just `AccountException` (the parent) alone
 * automatically covers every subtype.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleNotFound(e: AccountNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("Account not found: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> {
        logger.warn("Account request failed: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(CardNotFoundException::class)
    fun handleCardNotFound(e: CardNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("Card not found: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(LinkedAccountNotFoundException::class)
    fun handleLinkedAccountNotFound(e: LinkedAccountNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("Could not find the account to link: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(CardException::class)
    fun handleCardException(e: CardException): ResponseEntity<ErrorResponse> {
        logger.warn("Card request failed: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(PaymentNotFoundException::class)
    fun handlePaymentNotFound(e: PaymentNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("Payment not found: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(LinkedCardNotFoundException::class)
    fun handleLinkedCardNotFound(e: LinkedCardNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("Could not find the card to link: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(PaymentLinkedAccountNotFoundException::class)
    fun handlePaymentLinkedAccountNotFound(e: PaymentLinkedAccountNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("Could not find the linked account: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(PaymentException::class)
    fun handlePaymentException(e: PaymentException): ResponseEntity<ErrorResponse> {
        logger.warn("Payment request failed: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(e: InvalidCredentialsException): ResponseEntity<ErrorResponse> {
        logger.warn("Authentication failed: {}", e.message)
        return errorResponse(HttpStatus.UNAUTHORIZED, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(AuthException::class)
    fun handleAuthException(e: AuthException): ResponseEntity<ErrorResponse> {
        logger.warn("Auth request failed: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        logger.warn("Request validation failed: {}", message)
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unexpected error", e)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected error occurred.")
    }

    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<ErrorResponse> = ResponseEntity.status(status).body(ErrorResponse(status.value(), code, message, status.reasonPhrase))
}
