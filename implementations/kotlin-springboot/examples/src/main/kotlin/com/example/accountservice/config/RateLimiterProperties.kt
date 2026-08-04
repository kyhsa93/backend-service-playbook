package com.example.accountservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Rate-limiter instance settings under the same `resilience4j.ratelimiter.instances.*` keys the
 * resilience4j-spring-boot3 starter used to bind. The starter itself refuses to run on Spring
 * Boot 4 (its SpringBoot3Verifier throws at startup and no Boot 4 starter exists yet), so
 * [RateLimiterConfig] builds the RateLimiterRegistry from these properties directly — keeping
 * application.yml and the E2E tests' @DynamicPropertySource overrides working unchanged.
 */
@ConfigurationProperties(prefix = "resilience4j.ratelimiter")
data class RateLimiterProperties(
    val instances: Map<String, Instance> = emptyMap(),
) {
    data class Instance(
        val limitForPeriod: Int,
        val limitRefreshPeriod: Duration,
        val timeoutDuration: Duration = Duration.ZERO,
    )
}
