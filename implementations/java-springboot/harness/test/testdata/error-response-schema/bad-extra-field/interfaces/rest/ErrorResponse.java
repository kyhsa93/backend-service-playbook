package com.example.accountservice.interfaces.rest;

public record ErrorResponse(int statusCode, String code, String message, String error, String details) {
}
