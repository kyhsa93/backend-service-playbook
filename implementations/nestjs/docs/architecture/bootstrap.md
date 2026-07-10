# 앱 부트스트랩

> 아래는 실제 `src/main.ts`다. Swagger/CORS/전역 예외 필터/Graceful Shutdown 훅은 이 저장소에 아직 없다 — 필요해지면 추가할 확장 지점으로 문서 하단에 남겨둔다.

```typescript
// src/main.ts — 실제 코드
import { BadRequestException, ValidationPipe } from '@nestjs/common'
import { NestFactory } from '@nestjs/core'

import { AppModule } from '@/app-module'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { getPort, isProduction } from '@/config/app.config'

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

### 아직 적용하지 않은 확장 지점

- **Graceful Shutdown**: `enableShutdownHooks()` 미호출, `OnApplicationShutdown` 훅 없음 — 실제 남은 gap([graceful-shutdown.md](graceful-shutdown.md) 참고).
- **전역 예외 필터**: `HttpExceptionFilter` 없음 — `generateErrorResponse`가 만든 `HttpException`을 NestJS 기본 필터가 그대로 직렬화하므로 현재는 커스텀 필터가 불필요하다([error-handling.md](error-handling.md) 참고).
- **CORS/Swagger**: 현재 코드에 없음. 필요해지면 `app.enableCors(...)`/`SwaggerModule`을 추가한다.
