package com.example.accountservice.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Assembles the OpenAPI document's title/description/version + the `bearerAuth` security scheme —
 * the Kotlin/springdoc equivalent of NestJS's `DocumentBuilder` call inside `bootstrap()`
 * (see `docs/architecture/bootstrap.md`). Unlike NestJS, this is not invoked from `main()` — being a
 * `@Configuration` class with a `@Bean` factory method is enough for component scanning to register it,
 * and springdoc auto-assembles `/v3/api-docs` from it plus every `@Operation`/`@Schema` annotation found
 * by classpath scanning.
 *
 * The `bearerAuth` scheme is only registered here (in `components`), not attached globally via
 * `addSecurityItem` — `/auth/sign-in`/`/auth/sign-up` don't require a token, so each authenticated
 * Controller (Account/Card/Payment) opts in individually with a class-level
 * `@SecurityRequirement(name = "bearerAuth")` instead of every operation inheriting it by default.
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Account Service API")
                    .description(
                        "A DDD-based account/card/payment backend service. " +
                            "See docs/architecture/error-handling.md for the error-response schema shared by every non-2xx response.",
                    ).version("0.1.0"),
            ).components(
                Components()
                    .addSecuritySchemes(
                        "bearerAuth",
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .`in`(SecurityScheme.In.HEADER)
                            .name("Authorization"),
                    ),
            )
}
