package com.example.accountservice.common

/**
 * Converts application-wide exceptions to HTTP responses. Thanks to the `sealed class
 * AccountException` hierarchy, catching just the parent `AccountException` automatically covers
 * every subtype.
 */
class GlobalExceptionHandler
