plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    kotlin("plugin.jpa") version "2.4.10"
    id("org.springframework.boot") version "4.1.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
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

dependencies {
    // BOMs via Gradle's native platform() support — Spring Boot 4's Gradle plugin no longer
    // integrates with the io.spring.dependency-management plugin, whose Maven-style
    // "nearest wins" semantics were also why httpclient5/httpcore5 had to be hand-pinned
    // (the AWS SDK's apache5-client needs newer versions than the Boot BOM manages; Gradle's
    // native highest-version-wins resolution picks the newer ones on its own).
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    // Keeps ses/secretsmanager/sqs on a single, mutually-compatible version — pinning them
    // individually let a dependabot bump land on just one module and skew the shared
    // AWS SDK core/auth modules out of alignment, breaking at runtime with AbstractMethodError.
    implementation(platform("software.amazon.awssdk:bom:2.51.4"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Spring Boot 4's OpenTelemetry starter (OTel API + Micrometer tracing bridge + OTLP
    // exporters in one) — populates traceId/spanId into MDC automatically (picked up by
    // logback-spring.xml's LogstashEncoder) and exports spans via
    // management.opentelemetry.tracing.export.otlp.endpoint (application.yml).
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Jackson 3 (tools.jackson) — Spring Boot 4 auto-configures only the Jackson 3
    // ObjectMapper; the Kotlin module is auto-registered from the classpath as before.
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("software.amazon.awssdk:ses")
    implementation("software.amazon.awssdk:secretsmanager")
    implementation("software.amazon.awssdk:sqs")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    // The Boot-4-specific starter (same artifact line java-springboot uses) — the boot3 starter
    // hard-fails on Spring Boot 4 via its SpringBoot3Verifier.
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // TestRestTemplate lives in its own module as of Spring Boot 4 (package
    // org.springframework.boot.resttestclient) and is only auto-configured for tests that
    // declare @AutoConfigureTestRestTemplate. Its auto-configuration builds on
    // RestTemplateBuilder, which Boot 4 also split out of core into spring-boot-restclient.
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    // Gradle 9 stopped auto-adding this to the test runtime classpath — without it, `test`
    // fails immediately with "Failed to load JUnit Platform" before any test class runs.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The JDK's default HttpURLConnection-based client has a known limitation where it throws
    // a "cannot retry due to server authentication, in streaming mode" IOException when it
    // receives a 401 response after a POST (this is JDK's own behavior). Since the Auth E2E
    // tests need to verify an actual 401 (INVALID_CREDENTIALS), replace TestRestTemplate's
    // request factory with Apache HttpClient5, which doesn't have this limitation.
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-localstack")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.awaitility:awaitility:4.3.0")
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
    // No explicit ktlint engine pin: the plugin's default engine tracks the Kotlin version the
    // plugin was built against (1.3.1 pre-dated Kotlin 2.x and crashed its lexer under 2.4).
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
    }
}
