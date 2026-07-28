package com.example.accountservice.payment.application.query

import io.swagger.v3.oas.annotations.media.Schema

data class RefundReasonInsightsResult(
    @field:Schema(description = "A count per category, for refunds that have been classified so far — omits categories with 0 refunds.")
    val counts: List<RefundReasonCategoryCount>,
    @field:Schema(description = "The total number of classified refunds across all categories in the requested range.", example = "12")
    val totalClassified: Long,
) {
    data class RefundReasonCategoryCount(
        @field:Schema(
            description = "A refund-reason category.",
            example = "DEFECTIVE_PRODUCT",
            allowableValues = [
                "DEFECTIVE_PRODUCT",
                "WRONG_ITEM",
                "NOT_AS_DESCRIBED",
                "CHANGED_MIND",
                "LATE_DELIVERY",
                "DUPLICATE_CHARGE",
                "OTHER",
            ],
        )
        val category: String,
        @field:Schema(description = "How many classified refunds fall into this category, in the requested range.", example = "3")
        val count: Long,
    )
}
