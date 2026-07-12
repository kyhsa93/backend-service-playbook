package com.example.accountservice.common

import com.example.accountservice.account.domain.AccountException
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.interfaces.rest.ErrorResponse
import com.example.accountservice.card.domain.CardException
import com.example.accountservice.card.domain.CardNotFoundException
import com.example.accountservice.card.domain.LinkedAccountNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 애플리케이션 전역 예외 → HTTP 응답 변환.
 *
 * Controller마다 `@ExceptionHandler`를 흩어두지 않고 이 클래스 하나로 모은다 — 새 Controller를
 * 추가해도 이 핸들러를 그대로 재사용한다. `sealed class AccountException` 계층 덕분에
 * `AccountException`(부모) 하나만 잡아도 모든 하위 타입이 자동으로 커버된다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleNotFound(e: AccountNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("계좌를 찾을 수 없음: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> {
        logger.warn("계좌 요청 실패: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(CardNotFoundException::class)
    fun handleCardNotFound(e: CardNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("카드를 찾을 수 없음: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(LinkedAccountNotFoundException::class)
    fun handleLinkedAccountNotFound(e: LinkedAccountNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn("연결할 계좌를 찾을 수 없음: {}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(CardException::class)
    fun handleCardException(e: CardException): ResponseEntity<ErrorResponse> {
        logger.warn("카드 요청 실패: {}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        logger.warn("요청 검증 실패: {}", message)
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("예상치 못한 오류", e)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "예상치 못한 오류가 발생했습니다.")
    }

    private fun errorResponse(status: HttpStatus, code: String, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(status.value(), code, message, status.reasonPhrase))
}
