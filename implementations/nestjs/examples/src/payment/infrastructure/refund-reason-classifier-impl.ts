import Anthropic from '@anthropic-ai/sdk'
import { Injectable, Logger } from '@nestjs/common'

import { SecretService } from '@/common/application/service/secret-service'
import { getAnthropicApiKey, getRefundClassifierModel } from '@/config/llm.config'
import { RefundReasonClassifier } from '@/payment/application/service/refund-reason-classifier'
import { RefundReasonCategory, RefundReasonClassification } from '@/payment/domain/refund-reason-classification'

const CATEGORIES: RefundReasonCategory[] = [
  'defective_product',
  'not_as_described',
  'duplicate_charge',
  'changed_mind',
  'fraud_suspected',
  'other'
]

// Used whenever the classification can't be trusted (API error, refusal, malformed output).
// A neutral 'other'/no-fraud-signal result never blocks the refund flow on its own —
// RefundEligibilityService's other checks still run against it.
const FALLBACK_CLASSIFICATION: RefundReasonClassification = { category: 'other', fraudRiskScore: 0 }

const SYSTEM_PROMPT = 'You classify a customer refund request\'s free-text reason. Respond only through the given '
  + 'schema. Base fraudRiskScore purely on linguistic signals in the text itself (vagueness, internal '
  + 'inconsistency, urgency/pressure language, or an admission unrelated to the product) — never infer a high '
  + 'score just because the category is fraud_suspected, and never infer a low score just because it isn\'t.'

@Injectable()
export class RefundReasonClassifierImpl extends RefundReasonClassifier {
  private readonly logger = new Logger(RefundReasonClassifierImpl.name)
  private client?: Anthropic

  constructor(private readonly secretService: SecretService) {
    super()
  }

  // The API key is looked up from Secrets Manager only in production, exactly like
  // config/jwt.config.ts — every other environment (development/test) uses the environment
  // variable directly with no network call. Unlike jwt.config.ts (a config factory that runs
  // before the DI container exists), this lookup happens lazily on first use, inside a
  // DI-managed Technical Service, via the injected SecretService (see
  // docs/architecture/secret-manager.md). The NODE_ENV branch and the environment-variable
  // read itself both live in config/llm.config.ts, not here.
  private async getClient(): Promise<Anthropic> {
    if (this.client) return this.client
    const apiKey = await getAnthropicApiKey(this.secretService)
    this.client = new Anthropic({ apiKey })
    return this.client
  }

  public async classify(reason: string): Promise<RefundReasonClassification> {
    try {
      const client = await this.getClient()
      const response = await client.messages.create({
        model: getRefundClassifierModel(),
        max_tokens: 256,
        system: SYSTEM_PROMPT,
        messages: [{ role: 'user', content: reason }],
        output_config: {
          format: {
            type: 'json_schema',
            schema: {
              type: 'object',
              properties: {
                category: { type: 'string', enum: CATEGORIES },
                fraudRiskScore: { type: 'number' }
              },
              required: ['category', 'fraudRiskScore'],
              additionalProperties: false
            }
          }
        }
      })

      if (response.stop_reason === 'refusal') return FALLBACK_CLASSIFICATION

      const textBlock = response.content.find((block) => block.type === 'text')
      if (!textBlock || textBlock.type !== 'text') return FALLBACK_CLASSIFICATION

      const parsed = JSON.parse(textBlock.text) as { category: string; fraudRiskScore: number }
      if (!CATEGORIES.includes(parsed.category as RefundReasonCategory)) return FALLBACK_CLASSIFICATION

      return {
        category: parsed.category as RefundReasonCategory,
        fraudRiskScore: Math.min(1, Math.max(0, parsed.fraudRiskScore))
      }
    } catch (error) {
      // A classification failure is a technical-infrastructure concern, not a domain error — it
      // must never block a refund request. Swallow it here at the boundary and fall back.
      this.logger.warn({ message: 'Refund reason classification failed, using fallback', error: (error as Error).message })
      return FALLBACK_CLASSIFICATION
    }
  }
}
