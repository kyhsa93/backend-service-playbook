package com.example.accountservice.account.application.command;

public class CreateAccountService {
    private final String region;

    public CreateAccountService(AwsProperties awsProperties) {
        this.region = awsProperties.getRegion();
    }
}
