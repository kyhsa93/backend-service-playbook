plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
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
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("software.amazon.awssdk:ses:2.29.52")
    implementation("software.amazon.awssdk:secretsmanager:2.29.52")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JDK 기본 HttpURLConnection 기반 클라이언트는 POST 후 401 응답을 받으면 "cannot retry due to
    // server authentication, in streaming mode" IOException을 던지는 알려진 제약이 있다(JDK 자체
    // 동작). Auth E2E 테스트가 실제 401(INVALID_CREDENTIALS)을 검증해야 하므로, 이 제약이 없는
    // Apache HttpClient5로 TestRestTemplate의 요청 팩토리를 교체한다.
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
    testImplementation("io.mockk:mockk:1.13.13")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
