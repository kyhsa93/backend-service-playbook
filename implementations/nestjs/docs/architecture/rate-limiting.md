# Rate Limiting

`@nestjs/throttler`를 사용하여 API 요청 속도를 제한한다.

## 현재 구현

`examples/`에 `@nestjs/throttler`가 설치되어 있고, `src/app-module.ts`가 아래 "전역 설정" 코드 그대로 `ThrottlerModule.forRoot(getThrottlerConfig())`에 short(기본 1초 3회)/medium(기본 10초 20회)/long(기본 1분 100회) 3단 제한을 등록하고 `ThrottlerGuard`를 `APP_GUARD`로 바인딩한다 — 모든 엔드포인트에 자동 적용된다. 임계값은 `src/config/throttle.config.ts`가 `THROTTLE_*` 환경 변수로 override 가능하게 만든다(아래 "운영값 조정" 참고). 헬스체크 엔드포인트(`src/common/interface/health-controller.ts`, [graceful-shutdown.md](graceful-shutdown.md) 참고)는 `@SkipThrottle()`로 제한에서 제외했다. 이 문서의 "엔드포인트별 커스텀 제한" 섹션은 필요해지면 추가할 확장 패턴으로, 아직 `examples/`에는 적용되지 않았다.

## 설치

```bash
npm install @nestjs/throttler
```

## 전역 설정

```typescript
// src/config/throttle.config.ts — 실제 코드
import { ThrottlerModuleOptions } from '@nestjs/throttler'

function getEnvInt(name: string, defaultValue: number): number {
  const raw = process.env[name]
  if (!raw) return defaultValue
  const parsed = parseInt(raw, 10)
  return Number.isNaN(parsed) ? defaultValue : parsed
}

export function getThrottlerConfig(): ThrottlerModuleOptions {
  return {
    throttlers: [
      { name: 'short', ttl: getEnvInt('THROTTLE_SHORT_TTL_MS', 1000), limit: getEnvInt('THROTTLE_SHORT_LIMIT', 3) },
      { name: 'medium', ttl: getEnvInt('THROTTLE_MEDIUM_TTL_MS', 10000), limit: getEnvInt('THROTTLE_MEDIUM_LIMIT', 20) },
      { name: 'long', ttl: getEnvInt('THROTTLE_LONG_TTL_MS', 60000), limit: getEnvInt('THROTTLE_LONG_LIMIT', 100) }
    ]
  }
}
```

```typescript
// src/app-module.ts — 실제 코드
import { ThrottlerModule, ThrottlerGuard } from '@nestjs/throttler'
import { APP_GUARD } from '@nestjs/core'
import { getThrottlerConfig } from '@/config/throttle.config'

@Module({
  imports: [
    ThrottlerModule.forRoot(getThrottlerConfig())
  ],
  providers: [
    { provide: APP_GUARD, useClass: ThrottlerGuard }
  ]
})
export class AppModule {}
```

`APP_GUARD`로 등록하면 모든 엔드포인트에 자동 적용된다. 여러 throttler를 동시에 등록하여 단기/중기/장기 제한을 중첩할 수 있다. `getThrottlerConfig()`는 `database.config.ts`/`app.config.ts`와 같은 `ConfigModule`을 거치지 않는 순수 함수([config.md](config.md) 패턴)다 — `ThrottlerModule.forRoot()`는 `AppModule`의 `imports` 배열이 평가되는 시점(DI 컨테이너 조립 이전)에 값이 필요해 `ConfigService` 주입이 불가능하기 때문에, `data-source.ts`의 `getDatabaseUrl()`과 동일한 이유로 직접 호출 방식을 쓴다.

## 엔드포인트별 커스텀 제한 (확장 패턴 — 미적용)

특정 엔드포인트에 다른 제한을 적용하려면 `@Throttle()` 데코레이터를 사용한다.

```typescript
import { Throttle, SkipThrottle } from '@nestjs/throttler'

@Controller('orders')
export class OrderController {
  // 이 엔드포인트만 1분에 5회로 제한
  @Post()
  @Throttle({ long: { ttl: 60000, limit: 5 } })
  createOrder(@Body() body: CreateOrderRequestBody) { /* ... */ }

  // 헬스체크는 제한에서 제외
  @Get('health')
  @SkipThrottle()
  health() { return { status: 'ok' } }
}
```

### 클래스 레벨 제외

```typescript
// 전체 Controller를 제한에서 제외
@SkipThrottle()
@Controller('internal')
export class InternalController { /* ... */ }
```

## 운영값 조정

임계값(ttl/limit)을 바꾸려면 코드를 고치고 재배포하는 대신, 배포 환경에 아래 환경 변수를 설정하고 프로세스를 재기동한다 — 값이 없으면 `throttle.config.ts`의 기본값(현재 하드코딩되어 있던 것과 동일한 short 1초 3회 / medium 10초 20회 / long 1분 100회)을 그대로 쓴다.

| 환경 변수 | 기본값 | 의미 |
|---|---|---|
| `THROTTLE_SHORT_TTL_MS` / `THROTTLE_SHORT_LIMIT` | `1000` / `3` | 1초 윈도우 |
| `THROTTLE_MEDIUM_TTL_MS` / `THROTTLE_MEDIUM_LIMIT` | `10000` / `20` | 10초 윈도우 |
| `THROTTLE_LONG_TTL_MS` / `THROTTLE_LONG_LIMIT` | `60000` / `100` | 1분 윈도우 |

이 값들은 환경 변수 기반이라, go(`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`)·fastapi(`RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE`)와 동일하게 운영 중 임계값 조정에 코드 변경·재배포가 필요 없다.

## 응답 헤더

Throttler는 자동으로 응답 헤더에 제한 정보를 포함한다.

| 헤더 | 설명 |
|------|------|
| `X-RateLimit-Limit` | 허용된 최대 요청 수 |
| `X-RateLimit-Remaining` | 남은 요청 수 |
| `X-RateLimit-Reset` | 제한 초기화까지 남은 시간 (초) |

제한 초과 시 `429 Too Many Requests`를 반환한다.

## 원칙

- **전역 Guard로 등록**: `APP_GUARD`로 ThrottlerGuard를 등록하여 모든 엔드포인트에 기본 제한을 적용한다.
- **엔드포인트별 세분화**: 쓰기 API(POST, PUT, DELETE)는 읽기 API보다 제한을 강하게 설정한다.
- **내부 엔드포인트 제외**: 헬스체크, 메트릭 등 내부 엔드포인트는 `@SkipThrottle()`로 제외한다.
- **환경 변수로 제한값 관리**: 하드코딩하지 않고 `throttle.config.ts`의 `THROTTLE_*` 환경 변수로 배포 시점에 조정 가능하도록 한다(위 "운영값 조정" 참고).
