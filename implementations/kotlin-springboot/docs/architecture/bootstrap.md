# 앱 부트스트랩 — Kotlin Spring Boot

NestJS의 `main.ts`(`implementations/nestjs/docs/architecture/bootstrap.md`)와 비교하면 가장 먼저 눈에 띄는 차이는 **분량**이다. Spring Boot의 부트스트랩 함수는 거의 비어 있다 — 이는 게으름이 아니라 **선언적 설정(annotation + component scan)이 명령형 `main()` 호출을 대체**하기 때문이다.

## 실제 진입점

```kotlin
// AccountServiceApplication.kt — 실제 코드 전체
package com.example.accountservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}
```

이게 전부다. NestJS의 `bootstrap()`이 `app.useGlobalPipes(...)`, `app.useGlobalFilters(...)`, `app.enableCors(...)`, `SwaggerModule.setup(...)`을 한 함수 안에서 순서대로 호출하는 것과 달리, Kotlin/Spring Boot는 **같은 관심사를 각각 별도의 `@Component`/`@Configuration` 클래스로 흩어놓고 컴포넌트 스캔이 조립**한다. `main()`은 "어떤 것들을 켤지" 나열하지 않는다 — `@SpringBootApplication`이 이미 `@ComponentScan` + `@EnableAutoConfiguration` + `@Configuration`을 합친 메타 애노테이션이라, 클래스패스에 있는 설정 클래스를 자동으로 찾아 등록한다.

`runApplication<AccountServiceApplication>(*args)`는 Java의 `SpringApplication.run(AccountServiceApplication.class, args)`를 Kotlin의 [reified type parameter](https://kotlinlang.org/docs/inline-functions.html#reified-type-parameters)로 감싼 확장 함수다 — `.class` 토큰을 명시적으로 넘길 필요가 없다는 점이 Kotlin다운 차이지만, 실행되는 부트스트랩 시퀀스 자체는 순수 Java Spring Boot와 동일하다.

## 부트스트랩 시퀀스

```
1. runApplication<AccountServiceApplication>(*args)
2. ApplicationContext 생성, 환경(Environment) 구성
3. application.yml (+ application-{profile}.yml) 로드 → 환경 변수/시스템 프로퍼티로 오버라이드
4. @ComponentScan — com.example.accountservice 하위 패키지 전체 스캔
   → @Component/@Service/@Repository/@Configuration 빈 등록
5. @Bean 팩토리 메서드 실행 (예: SesConfig.sesClient())
6. 내장 톰캣(Embedded Tomcat) 시작, 포트 바인딩
7. ApplicationReadyEvent 발행 — 이 시점부터 트래픽 수신 가능
```

## `application.yml` 설정 로딩 순서

현재 `examples/src/main/resources/application.yml`은 이렇다.

```yaml
# application.yml — 실제 코드
spring:
  application:
    name: account-service
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

Spring Boot의 설정 소스는 우선순위가 낮은 것부터 높은 것 순으로 다음과 같이 병합된다(뒤에 오는 것이 앞의 것을 덮어쓴다):

```
application.yml (기본값)
  → application-{spring.profiles.active}.yml (프로파일별 오버라이드)
    → 환경 변수 (DATABASE_HOST 등)
      → 커맨드라인 인자 (--server.port=8081)
```

`@ConfigurationProperties` + `data class`로 관심사별 설정을 바인딩하는 방법과 Fail-fast 검증은 [config.md](config.md)에서 상세히 다룬다 — 부트스트랩 시점에는 "YAML → 환경 변수 오버라이드 → `@EnableConfigurationProperties`로 활성화한 프로퍼티 클래스에 바인딩"이라는 순서만 기억하면 된다.

## 전역 예외 처리 — `main()`이 아니라 `@RestControllerAdvice`

NestJS는 `app.useGlobalFilters(new HttpExceptionFilter())`처럼 부트스트랩 함수 안에서 명시적으로 등록한다. Spring Boot는 **`@RestControllerAdvice`가 붙은 클래스 자체가 컴포넌트 스캔으로 자동 등록**되므로 `main()`에 해당하는 코드가 없다.

```kotlin
// common/GlobalExceptionHandler.kt — 실제 코드 발췌, error-handling.md 참조
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(AccountException::class)
    fun handleAccountException(e: AccountException): ResponseEntity<ErrorResponse> = /* ... */
}
```

`AccountServiceApplication.kt`는 이 클래스의 존재조차 알 필요가 없다 — 패키지 안에 있고 `@RestControllerAdvice`가 붙어 있으면 그것으로 충분하다. `examples/`도 이 전역 핸들러 하나로 통합되어 있고, `AccountController`에는 `@ExceptionHandler` 메서드가 없다 — 상세는 [error-handling.md](error-handling.md) 참조.

## OpenAPI/Swagger — 현재 미도입

`examples/build.gradle.kts`를 확인한 결과 `springdoc-openapi` 의존성이 없다. NestJS의 `bootstrap.md`가 보여주는 `SwaggerModule.setup('api', app, document)`에 대응하는 코드가 이 저장소에는 없다는 뜻이다. 추가한다면:

```kotlin
// build.gradle.kts — 추가 필요 (현재 없음)
dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
}
```

```yaml
# application.yml — 추가 필요 (현재 없음)
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

`springdoc-openapi`는 NestJS처럼 부트스트랩 함수에서 `DocumentBuilder`로 문서를 조립하지 않는다 — 의존성을 추가하고 `@Operation`/`@Tag` 애노테이션을 컨트롤러에 붙이면 `/v3/api-docs`와 `/swagger-ui.html`이 **자동 등록**된다. 이 역시 "부트스트랩 함수가 아니라 클래스패스 스캔 + 애노테이션이 조립을 담당한다"는 Spring Boot의 일관된 패턴이다.

## CORS — 현재 미설정

`application.yml`에 CORS 관련 설정이 없고, `WebConfig`([cross-cutting-concerns.md](cross-cutting-concerns.md)에서 다루는 `HandlerInterceptor` 등록용 `@Configuration` 클래스)에도 CORS 매핑이 없다. 추가한다면 부트스트랩 함수가 아니라 `WebMvcConfigurer` 구현체에 선언한다.

```kotlin
// common/WebConfig.kt — 추가 필요 (현재 CORS 설정 없음)
@Configuration
class WebConfig(private val requestLoggingInterceptor: RequestLoggingInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(requestLoggingInterceptor)
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(*(System.getenv("CORS_ORIGIN")?.split(",")?.toTypedArray() ?: arrayOf("*")))
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowCredentials(true)
    }
}
```

## 정리 — NestJS 대비 무엇이 다른가

| 관심사 | NestJS (`main.ts`) | Kotlin/Spring Boot |
|---|---|---|
| 등록 방식 | `bootstrap()` 함수 안에서 명령형 호출 | 애노테이션 + 컴포넌트 스캔 (선언적) |
| 전역 검증 | `app.useGlobalPipes(new ValidationPipe(...))` | `@Valid` + Bean Validation ([cross-cutting-concerns.md](cross-cutting-concerns.md)) |
| 전역 에러 처리 | `app.useGlobalFilters(...)` | `@RestControllerAdvice` (자동 스캔, [error-handling.md](error-handling.md)) |
| API 문서 | `SwaggerModule.setup(...)` (부트스트랩에서 조립) | springdoc-openapi 의존성만 추가하면 자동 등록 (현재 미도입) |
| Graceful Shutdown | `app.enableShutdownHooks()` (명시적 호출 필요) | `SpringApplication.run()`이 shutdown hook을 이미 등록 ([graceful-shutdown.md](graceful-shutdown.md)) |
| 종료 코드 | `bootstrap()` 함수 자체 | `main()` — 사실상 `runApplication` 호출 한 줄 |

**핵심**: `main()`이 짧다고 해서 부트스트랩 단계에서 하는 일이 적은 것이 아니다 — 같은 관심사(검증, 에러 처리, 문서화, 종료 처리)를 클래스 단위로 분산하고 프레임워크가 스캔/조립하는 방식이 Spring Boot의 관용이다. 새 관심사를 추가할 때 `main()`을 수정하는 것이 아니라, 올바른 스테레오타입 애노테이션을 가진 새 클래스를 올바른 패키지에 두는 것이 Kotlin/Spring 개발자가 익혀야 할 첫 습관이다.

### 관련 문서

- [error-handling.md](error-handling.md) — `@RestControllerAdvice` 전역 처리 상세
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — `@Valid`, Filter/Interceptor 체인
- [graceful-shutdown.md](graceful-shutdown.md) — 종료 훅, Actuator 프로브
- [config.md](config.md) — `application.yml` 로딩과 `@ConfigurationProperties`
- [module-pattern.md](module-pattern.md) — 컴포넌트 스캔이 실제로 무엇을 등록하는지
