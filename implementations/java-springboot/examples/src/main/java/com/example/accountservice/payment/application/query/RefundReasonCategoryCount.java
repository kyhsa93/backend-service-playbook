package com.example.accountservice.payment.application.query;

import io.swagger.v3.oas.annotations.media.Schema;

public record RefundReasonCategoryCount(
        @Schema(
                        description = "A refund-reason category.",
                        example = "DEFECTIVE_PRODUCT",
                        allowableValues = {
                            "DEFECTIVE_PRODUCT",
                            "WRONG_ITEM",
                            "NOT_AS_DESCRIBED",
                            "CHANGED_MIND",
                            "LATE_DELIVERY",
                            "DUPLICATE_CHARGE",
                            "OTHER"
                        })
                String category,
        @Schema(
                        description =
                                "How many classified refunds fall into this category, in the requested range.")
                long count) {}
