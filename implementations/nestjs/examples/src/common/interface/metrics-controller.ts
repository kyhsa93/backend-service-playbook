import { Controller, Get, Header } from '@nestjs/common'
import { ApiInternalServerErrorResponse, ApiOkResponse, ApiOperation, ApiTags } from '@nestjs/swagger'
import { SkipThrottle } from '@nestjs/throttler'

import { Public } from '@/auth/public.decorator'
import { ErrorResponseBody } from '@/common/interface/dto/error-response-body'
import { metricsRegistry } from '@/common/metrics-registry'

// A non-domain-bearing common Controller — the same exception health-controller.ts already
// establishes (see harness/evaluators/rules/interface-no-infrastructure.evaluator.ts): a
// Prometheus scraper is the only caller, so this isn't part of any Bounded Context's own API surface.
@Controller('metrics')
@ApiTags('Metrics')
@SkipThrottle()
@Public()
@ApiInternalServerErrorResponse({ description: 'An unexpected error occurred while collecting metrics.', type: ErrorResponseBody })
export class MetricsController {
  @Get()
  @Header('Content-Type', metricsRegistry.contentType)
  @ApiOperation({
    operationId: 'getMetrics',
    summary: 'Prometheus metrics',
    description: 'Exposes HTTP request count/duration plus default Node.js process metrics in Prometheus text-exposition format, for a Prometheus server to scrape.'
  })
  @ApiOkResponse({ description: 'Metrics in Prometheus text-exposition format.' })
  metrics(): string {
    return metricsRegistry.metrics()
  }
}
