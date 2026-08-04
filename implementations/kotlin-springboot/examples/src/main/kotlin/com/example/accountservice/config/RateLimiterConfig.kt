package com.example.accountservice.config

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import io.github.resilience4j.ratelimiter.RateLimiterConfig as R4jRateLimiterConfig

/**
 * Assembles the [RateLimiterRegistry] that RateLimitingFilter consumes. See
 * [RateLimiterProperties] for why this is hand-wired instead of using the
 * resilience4j-spring-boot3 starter's auto-configuration.
 */
@Configuration
class RateLimiterConfig {
    @Bean
    fun rateLimiterRegistry(properties: RateLimiterProperties): RateLimiterRegistry {
        val registry = RateLimiterRegistry.ofDefaults()
        properties.instances.forEach { (name, instance) ->
            registry.rateLimiter(
                name,
                R4jRateLimiterConfig
                    .custom()
                    .limitForPeriod(instance.limitForPeriod)
                    .limitRefreshPeriod(instance.limitRefreshPeriod)
                    .timeoutDuration(instance.timeoutDuration)
                    .build(),
            )
        }
        return registry
    }
}
