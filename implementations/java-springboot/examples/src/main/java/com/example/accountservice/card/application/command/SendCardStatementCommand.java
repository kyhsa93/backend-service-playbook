package com.example.accountservice.card.application.command;

import java.time.YearMonth;

public record SendCardStatementCommand(YearMonth month) {}
