# 앱 부트스트랩 (Spring Boot)

> NestJS 대비 문서. 루트에는 대응 문서가 없다 — Spring Boot 자체의 부트스트랩 메커니즘은 프레임워크 특유의 주제라 root 24개 주제에 포함되지 않는다.

## 현재 실제 코드 — `AccountServiceApplication.java`

```java
// AccountServiceApplication.java — 실제 코드 전체
package com.example.accountservice;

import com.example.accountservice.config.AwsProperties;
import com.example.accountservice.config.SesProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class})
public class AccountServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

NestJS의 `main.ts`(수 줄에 걸쳐 `NestFactory.create()` 후 파이프/필터/CORS/Swagger를 명령형으로 하나씩 `app.use*()`하는 구조)와 달리, Spring Boot는 `@SpringBootApplication` + `@EnableConfigurationProperties` 애노테이션과 `SpringApplication.run()` 한 줄로 끝난다. 이는 기능이 없어서가 아니라, Spring Boot가 애노테이션 기반 자동 설정(auto-configuration)으로 같은 관심사를 분산 배치하기 때문이다 — 아래에서 이 차이를 항목별로 대응시킨다. `@EnableConfigurationProperties({AwsProperties.class, SesProperties.class})`가 [config.md](config.md)가 설명하는 fail-fast 검증 대상 빈들을 명시적으로 등록한다.

---

## `@SpringBootApplication` — 3개 메타 애노테이션의 합성

```java
@SpringBootApplication
// 실제로는 아래 3개를 합친 메타 애노테이션이다
// @Configuration           — 이 클래스 자체가 @Bean 정의를 가질 수 있는 설정 클래스
// @EnableAutoConfiguration — classpath의 의존성(spring-boot-starter-web, -data-jpa 등)을 보고 필요한 Bean을 자동 등록
// @ComponentScan           — 이 클래스가 위치한 패키지(com.example.accountservice) 하위를 전부 스캔해 @Component/@Service/@Repository/@Controller를 빈으로 등록
public class AccountServiceApplication { ... }
```

- **`@EnableAutoConfiguration`**: NestJS는 `imports: [TypeOrmModule.forRoot(...), ConfigModule.forRoot(...)]`처럼 각 모듈을 `AppModule`에 명시적으로 나열해야 한다. Spring Boot는 `build.gradle`에 `spring-boot-starter-data-jpa`가 있으면 `DataSource`/`EntityManagerFactory`/`JpaRepository` 지원 Bean들이 **classpath 존재만으로 자동 등록**된다. 이 저장소는 `AccountServiceApplication`이 최상위 패키지(`com.example.accountservice`)에 있으므로 `@ComponentScan`이 `account/` 하위 전체(내부의 `notification` Technical Service 포함)를 자동으로 커버한다 — 명시적 `imports` 목록이 없다.
- **위치가 스캔 범위를 결정한다**: `AccountServiceApplication`을 다른 패키지로 옮기면 형제/자식이 아닌 패키지의 `@Service`/`@Repository`가 스캔에서 누락된다. 최상위 패키지에 두는 현재 배치가 올바르다.

---

## `SpringApplication.run()` 부트스트랩 순서

`SpringApplication.run(AccountServiceApplication.class, args)` 한 줄이 내부적으로 아래 순서를 실행한다:

1. **`Environment` 준비** — `application.yml` 로딩, 활성 프로필(`spring.profiles.active`) 결정, OS 환경 변수/시스템 프로퍼티를 `PropertySource` 우선순위에 따라 병합
2. **`ApplicationContext` 생성** — `@Configuration` 클래스(`SesConfig` 등) 파싱
3. **`@ComponentScan`** — `@Component`/`@Service`/`@Repository`/`@RestController` 빈 등록
4. **Bean 인스턴스화 + 의존성 주입** — 생성자 주입 순서로 그래프 구성 ([module-pattern.md](module-pattern.md) 참고)
5. **`@ConfigurationProperties` 바인딩 + `@Validated` 검증** — 실패 시 `ApplicationContext` 초기화 중단 (fail-fast, [config.md](config.md) 참고)
6. **내장 톰캣(Servlet 컨테이너) 기동** — `server.port`(기본 8080)로 리스닝 시작
7. **`ApplicationReadyEvent` 발행** — 이 시점부터 트래픽 수신 가능

이 순서 중 1(설정 로딩)과 5(검증)가 NestJS의 `ConfigModule.forRoot({ validate: validateConfig })`에 대응한다 — 다만 NestJS는 `bootstrap()` 함수 내부에서 명시적으로 조립하는 반면, Spring Boot는 `@ConfigurationProperties` 클래스를 선언하기만 하면 `SpringApplication.run()`이 자동으로 이 순서에 끼워 넣는다.

### `application.yml` 설정 로딩 순서 (우선순위 낮음 → 높음)

```
1. application.yml (기본값)
2. application-{profile}.yml (spring.profiles.active로 활성화된 프로필 오버라이드)
3. OS 환경 변수 (${AWS_REGION} 등 플레이스홀더로 참조된 값)
4. 커맨드라인 인자 (--server.port=9090 등)
```

이 저장소는 현재 `application.yml` + `application-prod.yml`(운영 프로필 오버라이드, 기본값 없음) 두 파일을 갖는다 — `application-database.yml`/`application-aws.yml`/`application-jwt.yml`/`application-local.yml`처럼 세분화된 `spring.config.import` 구성은 아직 제안 단계다. 관심사별 분리와 프로필 전략은 [config.md](config.md) "관심사별 설정 파일 분리"에서 상세히 다룬다. 여기서는 로딩 순서 자체가 부트스트랩 1단계에 속한다는 점만 짚는다.

---

## 전역 예외 처리 배선 — 현재 Controller-local, `@RestControllerAdvice`로 전역화 필요

NestJS의 `app.useGlobalFilters(new HttpExceptionFilter())`처럼 부트스트랩 시점에 명시적으로 등록하는 전역 필터가 Spring Boot에는 없다 — 대신 `@RestControllerAdvice`가 붙은 클래스는 `@ComponentScan`이 자동으로 발견해 **모든** `@RestController`에 적용한다. 등록이 `main()` 안이 아니라 클래스 선언 자체에 있다는 점이 NestJS와의 핵심 차이다.

현재 코드는 `AccountController` 내부에 `@ExceptionHandler(AccountException.class)`가 있어 `AccountController`에만 적용된다. [error-handling.md](error-handling.md) "Validation 실패 응답" 섹션이 제안하는 `@RestControllerAdvice` 전역 클래스가 아직 없다 — 도메인이 하나뿐이라 지금은 문제가 드러나지 않지만, 두 번째 `@RestController`가 생기면 `MethodArgumentNotValidException` 핸들러를 중복 정의해야 한다.

```java
// common/web/GlobalExceptionHandler.java — 제안, error-handling.md 참고
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) { /* ... */ }
}
```

`@RestControllerAdvice` 클래스는 `main()`에 등록 코드를 추가할 필요 없이 `common/` 같은 공용 패키지에 두기만 하면 `@ComponentScan`이 자동으로 활성화한다 — [shared-modules.md](shared-modules.md) 참고.

---

## OpenAPI/Swagger — 현재 미도입, 도입 시 패턴

`build.gradle`에 `springdoc-openapi` 의존성이 없다 — 이 저장소는 아직 API 문서를 자동 생성하지 않는다. 도입 시:

```groovy
// build.gradle — 추가 필요 (제안)
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'
```

springdoc은 NestJS의 `SwaggerModule.createDocument()`처럼 `main()`에서 문서 객체를 조립할 필요가 없다 — 의존성만 추가하면 `@RestController`/`@RequestMapping`/`@Valid` 애노테이션을 리플렉션으로 읽어 `/v3/api-docs`, `/swagger-ui.html`을 **자동** 노출한다. 커스터마이징(제목, 버전, Bearer 인증 스킴)이 필요하면 `@Configuration` 클래스에 `@Bean OpenAPI` 하나만 추가한다:

```java
// common/config/OpenApiConfig.java — 도입 시 제안
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Account Service API").version("v1"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
```

---

## CORS 설정 — 현재 미도입, 기존 `WebConfig`를 확장한다

`config/WebConfig.java`가 이미 실재한다 — `RequestLoggingInterceptor`를 등록하는 `WebMvcConfigurer` 구현체다([cross-cutting-concerns.md](cross-cutting-concerns.md) 참고). CORS를 도입할 때는 **새 클래스를 만들지 않고 이 기존 `WebConfig`에 `addCorsMappings(...)`를 추가**한다 — `common/config/WebConfig.java`라는 이름/위치로 별도 클래스를 새로 만들면 실제 `config/WebConfig.java`와 이름이 충돌한다.

```java
// config/WebConfig.java — 실제 코드에 CORS 추가 시 (제안, addCorsMappings만 신규)
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RequestLoggingInterceptor requestLoggingInterceptor;

    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.isBlank() ? new String[]{} : allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowCredentials(true);
    }
}
```

Spring Security가 이미 도입되어 있으므로([authentication.md](authentication.md) 참고) CORS는 `WebMvcConfigurer.addCorsMappings(...)` 대신 `SecurityConfig`의 `SecurityFilterChain`에 `.cors(...)` 설정으로 추가하는 편이 낫다 — `WebMvcConfigurer`와 `SecurityFilterChain` 양쪽에 중복 설정하면 필터 순서에 따라 예기치 않게 하나만 적용된다. 이 저장소는 Spring Security를 이미 쓰므로 CORS 도입 시 `SecurityConfig` 쪽에 두는 것을 권장하고, 위 `WebConfig.addCorsMappings(...)` 예시는 Spring Security 없이 최소로 도입할 경우의 참고용이다.

---

## Actuator / 헬스체크 — 현재 미도입

`build.gradle`에 `spring-boot-starter-actuator`가 없다. 도입 시 부트스트랩에 코드 변경이 필요 없다 — 의존성 추가와 `application.yml` 설정만으로 `/actuator/health/liveness`, `/actuator/health/readiness`가 자동 노출된다. 상세 설정과 graceful shutdown 연동은 [container.md](container.md) "헬스체크 엔드포인트"와 [graceful-shutdown.md](graceful-shutdown.md)에서 이미 다룬다 — 여기서는 이것이 부트스트랩 단계의 일부(자동 등록되는 Actuator 엔드포인트)라는 점만 짚는다.

---

## 요약 — NestJS `main.ts` vs Spring Boot 부트스트랩 대응표

| NestJS `main.ts` | Spring Boot 대응 | 배치 위치 |
|---|---|---|
| `NestFactory.create(AppModule)` | `SpringApplication.run(AccountServiceApplication.class, args)` | `AccountServiceApplication.java` |
| `app.enableShutdownHooks()` | 기본 활성 (`server.shutdown: graceful` 설정만 추가) | `application.yml` |
| `app.useGlobalPipes(new ValidationPipe())` | `@Valid` + `spring-boot-starter-validation` (이미 적용됨) | 각 Controller 메서드 파라미터 |
| `app.useGlobalFilters(new HttpExceptionFilter())` | `@RestControllerAdvice` 클래스 (현재 미도입) | `common/web/GlobalExceptionHandler.java` |
| `app.enableCors({...})` | `WebMvcConfigurer.addCorsMappings()` (현재 미도입) | `config/WebConfig.java`(기존 파일에 추가 — 새 클래스를 만들지 않는다) |
| `SwaggerModule.setup('api', app, document)` | `springdoc-openapi` 의존성 추가만으로 자동 노출 (현재 미도입) | `build.gradle` + 선택적 `OpenApiConfig` |
| `app.listen(process.env.PORT ?? 3000)` | `server.port` (기본 8080) | `application.yml` |

핵심 차이: NestJS는 부트스트랩 관심사들을 `main.ts`에 **명령형으로 나열**하지만, Spring Boot는 각 관심사를 **선언적 애노테이션 + classpath 자동 설정**으로 분산 배치한다 — `main()`은 `SpringApplication.run()` 한 줄만 남는다.

---

### 관련 문서

- [error-handling.md](error-handling.md) — `@RestControllerAdvice` 전역 예외 처리 상세
- [config.md](config.md) — `application.yml` 로딩, 프로필, `@ConfigurationProperties`
- [container.md](container.md) — Actuator 헬스체크, 컨테이너 환경
- [graceful-shutdown.md](graceful-shutdown.md) — `server.shutdown: graceful`
- [authentication.md](authentication.md) — Spring Security 도입 시 CORS 배치 변경
