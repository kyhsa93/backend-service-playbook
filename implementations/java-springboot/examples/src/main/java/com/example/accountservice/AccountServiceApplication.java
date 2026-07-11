package com.example.accountservice;

import com.example.accountservice.config.AwsProperties;
import com.example.accountservice.config.JwtProperties;
import com.example.accountservice.config.SesProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class, JwtProperties.class})
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
