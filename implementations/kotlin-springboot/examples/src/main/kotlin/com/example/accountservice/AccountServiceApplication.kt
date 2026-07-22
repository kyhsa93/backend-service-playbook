package com.example.accountservice

import com.example.accountservice.config.AwsProperties
import com.example.accountservice.config.JwtProperties
import com.example.accountservice.config.SesProperties
import com.example.accountservice.config.SqsProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(AwsProperties::class, SesProperties::class, JwtProperties::class, SqsProperties::class)
@EnableScheduling // Enables OutboxPoller's @Scheduled(fixedDelay = 1000)
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}
