package com.example.accountservice.payment.interfaces.rest

import com.example.accountservice.account.interfaces.rest.ErrorResponse
import com.example.accountservice.payment.application.query.GetRefundReasonInsightsService
import com.example.accountservice.payment.application.query.RefundReasonInsightsResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * A cross-payment ops-analytics endpoint — not nested under `/payments/{paymentId}/`, since it
 * aggregates across every refund rather than being scoped to one payment or one owner. See
 * [com.example.accountservice.payment.application.query.RefundReasonInsightsQuery]'s KDoc for why this
 * stays behind the same baseline authentication as every other endpoint instead of a dedicated admin
 * role.
 */
@RestController
@RequestMapping("/refunds")
@Tag(name = "Payment")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "The bearer token is missing, malformed, or invalid.",
        content = [Content(schema = Schema(implementation = ErrorResponse::class))],
    ),
)
class RefundReasonInsightsController(
    private val getRefundReasonInsightsService: GetRefundReasonInsightsService,
) {
    @GetMapping("/reason-insights")
    @Operation(
        summary = "Get refund-reason category counts for ops analytics",
        description =
            "Returns how many refunds fall into each auto-classified reason category (e.g. DEFECTIVE_PRODUCT, " +
                "CHANGED_MIND), optionally narrowed to a date range. Classification runs asynchronously after a refund " +
                "is requested and never influences whether that refund is approved — this is a read-only reporting " +
                "view across every refund, not scoped to the caller's own payments.",
    )
    @ApiResponse(responseCode = "200", description = "The insights were computed.")
    fun getRefundReasonInsights(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fromDate: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) toDate: LocalDate?,
    ): RefundReasonInsightsResult = getRefundReasonInsightsService.getInsights(fromDate, toDate)
}
