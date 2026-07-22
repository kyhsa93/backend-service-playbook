import { ThrottlerModuleOptions } from '@nestjs/throttler'

// If the environment variable is absent or doesn't parse as a number, keep the previously hardcoded value as the default.
function getEnvInt(name: string, defaultValue: number): number {
  const raw = process.env[name]
  if (!raw) return defaultValue
  const parsed = parseInt(raw, 10)
  return Number.isNaN(parsed) ? defaultValue : parsed
}

// Makes the short/medium/long 3-tier thresholds adjustable via environment variables — to
// change a value in production, just update the container's environment variable and restart,
// with no code change (see rate-limiting.md, "Adjusting Production Values").
export function getThrottlerConfig(): ThrottlerModuleOptions {
  return {
    throttlers: [
      { name: 'short', ttl: getEnvInt('THROTTLE_SHORT_TTL_MS', 1000), limit: getEnvInt('THROTTLE_SHORT_LIMIT', 3) },
      { name: 'medium', ttl: getEnvInt('THROTTLE_MEDIUM_TTL_MS', 10000), limit: getEnvInt('THROTTLE_MEDIUM_LIMIT', 20) },
      { name: 'long', ttl: getEnvInt('THROTTLE_LONG_TTL_MS', 60000), limit: getEnvInt('THROTTLE_LONG_LIMIT', 100) }
    ]
  }
}
