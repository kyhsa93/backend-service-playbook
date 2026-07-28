package com.example.accountservice.account.domain

/**
 * Placed in the domain layer (not application) even though it is only ever produced by the
 * application-layer `SpendingForecastModel` Technical Service — [SpendingForecast] (a domain
 * read-model row) has a field of this type, and the domain layer may never import from application
 * (layer-architecture.md, domain-layer-isolation). `SpendingForecastModel`'s prediction result type
 * imports this enum from domain instead, which is the allowed direction.
 */
enum class ForecastConfidence { LOW, MEDIUM, HIGH }
