plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
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
        // software.amazon.awssdk:ses는 2.48.3부터 기본 동기 HTTP 클라이언트로
        // apache5-client(httpclient5 기반)를 함께 끌어온다. 이 apache5-client가 요구하는
        // httpclient5/httpcore5 버전(5.6.2/5.4.3)이 Spring Boot 3.3.5 BOM이 관리하는 버전
        // (5.3.1/5.2.5)보다 높아서, BOM이 낮은 버전으로 다운그레이드해버리면 AWS SDK의
        // Apache5HttpClient가 런타임에 NoClassDefFoundError를 던진다. BOM보다 우선하도록
        // 명시적으로 버전을 고정한다.
        dependency("org.apache.httpcomponents.client5:httpclient5:5.6.2")
        dependency("org.apache.httpcomponents.core5:httpcore5:5.4.3")
        dependency("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
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
    implementation("software.amazon.awssdk:ses:2.48.3")
    implementation("software.amazon.awssdk:secretsmanager:2.29.52")
    implementation("software.amazon.awssdk:sqs:2.29.52")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
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

// 코드 스타일 lint. 포맷 위반은 `./gradlew ktlintFormat`으로 자동 수정하고,
// CI에서는 `ktlintCheck`(build에 연동)로 검증만 한다. 최대 엄격도가 아니라
// 기본 룰셋 + 표준 라이브러리 import 정렬 정도의 온건한 설정을 유지한다.
ktlint {
    version.set("1.3.1")
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
    }
}
