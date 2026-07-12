package com.example.accountservice.common

/**
 * 애플리케이션 전역 예외 → HTTP 응답 변환. `sealed class AccountException` 계층 덕분에
 * `AccountException`(부모) 하나만 잡아도 모든 하위 타입이 자동으로 커버된다.
 */
class GlobalExceptionHandler
