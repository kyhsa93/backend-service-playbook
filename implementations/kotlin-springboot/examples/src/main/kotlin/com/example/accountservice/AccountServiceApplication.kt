package com.example.accountservice

import com.example.accountservice.config.AwsProperties
import com.example.accountservice.config.JwtProperties
import com.example.accountservice.config.SesProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AwsProperties::class, SesProperties::class, JwtProperties::class)
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}
