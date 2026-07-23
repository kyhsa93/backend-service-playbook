package com.example.accountservice;

import com.example.accountservice.config.AwsProperties;
import com.example.accountservice.config.FraudScorerProperties;
import com.example.accountservice.config.JwtProperties;
import com.example.accountservice.config.RefundClassifierProperties;
import com.example.accountservice.config.SesProperties;
import com.example.accountservice.config.SqsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling — enables outbox/OutboxPoller's @Scheduled(fixedDelay = 1000).
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    AwsProperties.class,
    SesProperties.class,
    JwtProperties.class,
    SqsProperties.class,
    RefundClassifierProperties.class,
    FraudScorerProperties.class
})
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
