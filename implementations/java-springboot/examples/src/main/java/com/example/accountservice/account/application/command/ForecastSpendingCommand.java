package com.example.accountservice.account.application.command;

/**
 * The month being predicted, YYYY-MM — always the current month, since the job trains on history
 * strictly before it (see {@code ForecastSpendingService}).
 */
public record ForecastSpendingCommand(String forecastMonth) {}
