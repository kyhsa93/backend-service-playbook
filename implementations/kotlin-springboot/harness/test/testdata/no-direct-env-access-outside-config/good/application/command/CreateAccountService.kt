package com.example.accountservice.account.application.command

import com.example.accountservice.config.AwsProperties
import org.springframework.stereotype.Service

@Service
class CreateAccountService(
    private val awsProperties: AwsProperties,
)
