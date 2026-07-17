# 앱 부트스트랩

> 아래는 실제 `src/main.ts`다.

```typescript
// src/main.ts — 실제 코드
import { BadRequestException, ValidationPipe } from '@nestjs/common'
import { NestFactory } from '@nestjs/core'
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger'

import { AppModule } from '@/app-module'
import { HttpExceptionFilter } from '@/common/http-exception.filter'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { getCorsOrigins, getPort, isProduction } from '@/config/app.config'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, {
    logger: isProduction()
      ? ['error', 'warn', 'log']
      : ['error', 'warn', 'log', 'debug', 'verbose']
  })

  // 전역 ValidationPipe — class-validator 자동 적용, 실패 시 code 포함 응답 구성
  app.useGlobalPipes(new ValidationPipe({
    whitelist: true,
    transform: true,
    exceptionFactory: (errors) => {
      const message = errors.flatMap((error) => Object.values(error.constraints ?? {}))
      return new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })
    }
  }))

  // 요청 로깅 인터셉터
  app.useGlobalInterceptors(new LoggingInterceptor())

  // 전역 예외 필터 — HttpException이 아닌 미처리 예외도 표준 에러 응답 형식으로 변환
  app.useGlobalFilters(new HttpExceptionFilter())

  // CORS
  app.enableCors({
    origin: getCorsOrigins(),
    methods: ['GET', 'POST', 'PATCH', 'PUT', 'DELETE', 'OPTIONS'],
    credentials: true
  })

  // Swagger
  const swaggerConfig = new DocumentBuilder()
    .setTitle('Account Service API')
    .setDescription('DDD 기반 Account 도메인 예시 서비스 API 문서')
    .setVersion('0.1.0')
    .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }, 'token')
    .build()
  const swaggerDocument = SwaggerModule.createDocument(app, swaggerConfig)
  SwaggerModule.setup('docs', app, swaggerDocument)

  // Graceful Shutdown — SIGTERM/SIGINT 수신 시 Nest lifecycle 훅(onModuleDestroy 등) 실행
  app.enableShutdownHooks()

  await app.listen(getPort())
}

bootstrap()
```

### 설정 요약

| 설정 | 역할 |
|------|------|
| `logger` 옵션 | `isProduction()`이면 debug/verbose 로그 제외 |
| `ValidationPipe` | class-validator 데코레이터 자동 적용, `exceptionFactory`로 `VALIDATION_FAILED` code 포함 응답 구성 ([error-handling.md](error-handling.md) 참고) |
| `LoggingInterceptor` | 요청 메서드/경로/처리 시간 로깅 |
| `HttpExceptionFilter` | 전역 예외 필터. `HttpException`은 표준 형식으로 직렬화하고, 그 외 미처리 예외(plain `Error` 등)도 스택트레이스를 그대로 노출하지 않고 `{ statusCode: 500, code: 'INTERNAL_ERROR', message, error }` 형태로 변환한다 ([error-handling.md](error-handling.md) 참고) |
| `enableCors(...)` | `config/app.config.ts`의 `getCorsOrigins()`가 운영(`isProduction()`)에서는 `CORS_ORIGIN` 환경 변수(콤마 구분)로 허용 출처를 제한하고, 그 외 환경에서는 전체 허용(`true`)을 반환 |
| `DocumentBuilder` + `SwaggerModule` | `/docs` 경로에 OpenAPI 문서 노출. `addBearerAuth(..., 'token')`은 컨트롤러의 `@ApiBearerAuth('token')`과 짝을 맞춘 이름이다 |
| `enableShutdownHooks()` | SIGTERM/SIGINT 수신 시 `OnApplicationShutdown` 등 Nest 라이프사이클 훅이 동작하도록 활성화 ([graceful-shutdown.md](graceful-shutdown.md) 참고) |

### 확장 지점

- **Graceful Shutdown 세부 패턴**: `enableShutdownHooks()`와 `HealthController`/`ShutdownState`가 함께 적용되어 있다([graceful-shutdown.md](graceful-shutdown.md) 참고) — `BeforeApplicationShutdown` 훅에서 readiness를 먼저 실패로 전환한 뒤 `/health/live`·`/health/ready`로 노출한다. Redis·메시지 큐 연결 정리는 이 저장소에 Redis·메시지 큐가 없어 대상이 아니다. TypeORM 연결은 Nest/TypeORM이 자체적으로 정리하므로 별도 처리가 불필요하다.
