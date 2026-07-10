package com.example.accountservice.secret.application.service

interface SecretService {
    fun getSecret(secretId: String): String
}
