plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
    dependencies {
        // Since 2.48.3, software.amazon.awssdk:ses pulls in apache5-client (based on
        // httpclient5) as its default synchronous HTTP client. The httpclient5/httpcore5
        // versions apache5-client requires (5.6.2/5.4.3) are newer than the versions managed
        // by the Spring Boot 3.3.5 BOM (5.3.1/5.2.5), so if the BOM downgrades them to its
        // lower versions, the AWS SDK's Apache5HttpClient throws a NoClassDefFoundError at
        // runtime. Pin the versions explicitly so they take precedence over the BOM.
        dependency("org.apache.httpcomponents.client5:httpclient5:5.6.2")
        dependency("org.apache.httpcomponents.core5:httpcore5:5.4.3")
        dependency("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Spring Boot 3's standard OpenTelemetry bridge — populates traceId/spanId into MDC
    // automatically (picked up by logback-spring.xml's LogstashEncoder), and OtlpAutoConfiguration
    // exports spans via management.otlp.tracing.endpoint (application.yml).
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("software.amazon.awssdk:ses:2.48.3")
    implementation("software.amazon.awssdk:secretsmanager:2.29.52")
    implementation("software.amazon.awssdk:sqs:2.29.52")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.4.0")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The JDK's default HttpURLConnection-based client has a known limitation where it throws
    // a "cannot retry due to server authentication, in streaming mode" IOException when it
    // receives a 401 response after a POST (this is JDK's own behavior). Since the Auth E2E
    // tests need to verify an actual 401 (INVALID_CREDENTIALS), replace TestRestTemplate's
    // request factory with Apache HttpClient5, which doesn't have this limitation.
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.awaitility:awaitility:4.2.2")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Code style lint. Format violations are auto-fixed with `./gradlew ktlintFormat`;
// CI only verifies via `ktlintCheck` (wired into build). Rather than maximum strictness,
// keep a moderate configuration — the default ruleset plus standard-library import sorting.
ktlint {
    version.set("1.3.1")
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
    }
}
