import { SecretService } from '@/common/application/service/secret-service'

const DEFAULT_REFUND_CLASSIFIER_MODEL = 'claude-opus-4-8'

export function getRefundClassifierModel(): string {
  return process.env.REFUND_CLASSIFIER_MODEL ?? DEFAULT_REFUND_CLASSIFIER_MODEL
}

// Looks up the Anthropic API key from Secrets Manager only in production — every other
// environment (development/test) uses the environment variable directly, with no network call.
// Same NODE_ENV-gating convention as jwt.config.ts, just encapsulated here (all process.env
// access must live in src/config/*.config.ts — see docs/architecture/config.md) rather than in
// the calling Technical Service implementation.
export async function getAnthropicApiKey(secretService: SecretService): Promise<string> {
  if (process.env.NODE_ENV !== 'production') {
    return process.env.ANTHROPIC_API_KEY ?? 'dev-anthropic-key'
  }
  return secretService.getSecret('app/anthropic')
}
