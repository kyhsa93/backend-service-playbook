import { ThrottlerModuleOptions } from '@nestjs/throttler'

// 환경 변수가 없거나 숫자로 파싱되지 않으면 기존 하드코딩 값을 기본값으로 유지한다.
function getEnvInt(name: string, defaultValue: number): number {
  const raw = process.env[name]
  if (!raw) return defaultValue
  const parsed = parseInt(raw, 10)
  return Number.isNaN(parsed) ? defaultValue : parsed
}

// short/medium/long 3단 임계값을 환경 변수로 조정 가능하게 한다 — 운영 중 값을 바꾸려면
// 코드 변경 없이 컨테이너 환경 변수만 갱신하고 재기동하면 된다(rate-limiting.md "운영값 조정" 참고).
export function getThrottlerConfig(): ThrottlerModuleOptions {
  return {
    throttlers: [
      { name: 'short', ttl: getEnvInt('THROTTLE_SHORT_TTL_MS', 1000), limit: getEnvInt('THROTTLE_SHORT_LIMIT', 3) },
      { name: 'medium', ttl: getEnvInt('THROTTLE_MEDIUM_TTL_MS', 10000), limit: getEnvInt('THROTTLE_MEDIUM_LIMIT', 20) },
      { name: 'long', ttl: getEnvInt('THROTTLE_LONG_TTL_MS', 60000), limit: getEnvInt('THROTTLE_LONG_LIMIT', 100) }
    ]
  }
}
