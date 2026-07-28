package com.example.accountservice.payment.application.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RefundReasonInsightsResult(
        @Schema(
                        description =
                                "A count per category, for refunds that have been classified so far — omits"
                                        + " categories with 0 refunds.")
                List<RefundReasonCategoryCount> counts,
        @Schema(
                        description =
                                "The total number of classified refunds across all categories in the requested"
                                        + " range.")
                long totalClassified) {}
