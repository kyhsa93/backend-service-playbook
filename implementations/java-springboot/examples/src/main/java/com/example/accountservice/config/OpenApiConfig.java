package com.example.accountservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the OpenAPI document's title/description/version and registers the bearer-JWT security
 * scheme (see docs/architecture/api-response.md "Machine-readable API documentation (OpenAPI)" and
 * docs/architecture/bootstrap.md's OpenAPI/Swagger section). Unlike NestJS's {@code main.ts} (where
 * {@code DocumentBuilder}/{@code SwaggerModule.setup()} must be assembled imperatively), simply
 * adding the springdoc dependency plus this single {@code @Bean} is enough — springdoc discovers
 * every REST controller class via reflection and auto-exposes {@code /v3/api-docs} and {@code
 * /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_JWT_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Account Service API")
                                .description(
                                        "API documentation for the DDD-based Account domain example"
                                                + " service")
                                .version("0.1.0"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        BEARER_JWT_SCHEME,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")));
    }
}
