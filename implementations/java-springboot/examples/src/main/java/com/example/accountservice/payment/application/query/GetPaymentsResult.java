package com.example.accountservice.payment.application.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GetPaymentsResult(
        @Schema(description = "The requester's payments, newest first.")
                List<GetPaymentResult> payments,
        @Schema(
                        description =
                                "The total number of payments for this requester (not just the current page).")
                long count) {}
