package com.example.accountservice.payment.application.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GetRefundsResult(
        @Schema(description = "The payment's refunds, newest first.") List<GetRefundResult> refunds,
        @Schema(
                        description =
                                "The total number of refunds for this payment (not just the current page).")
                long count) {}
