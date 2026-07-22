import { Controller, Get, ServiceUnavailableException } from '@nestjs/common'
import { ApiOkResponse, ApiOperation, ApiServiceUnavailableResponse, ApiTags } from '@nestjs/swagger'
import { SkipThrottle } from '@nestjs/throttler'

import { ShutdownState } from '@/common/infrastructure/shutdown-state'
import { Public } from '@/auth/public.decorator'

@Controller('health')
@ApiTags('Health')
@SkipThrottle()
@Public()
export class HealthController {
  constructor(private readonly shutdownState: ShutdownState) {}

  @Get('live')
  @ApiOperation({
    operationId: 'getLiveness',
    summary: 'Liveness probe',
    description: 'Reports whether the process itself is alive. Always returns 200, even while the process is shutting down — a load balancer/orchestrator should use `ready`, not this, to decide whether to route traffic.'
  })
  @ApiOkResponse({ description: 'The process is alive — returns 200 even while shutting down.' })
  live() {
    return { status: 'ok' }
  }

  @Get('ready')
  @ApiOperation({
    operationId: 'getReadiness',
    summary: 'Readiness probe',
    description: 'Reports whether the process is ready to accept new requests. Used by a load balancer/orchestrator to stop routing traffic during graceful shutdown.'
  })
  @ApiOkResponse({ description: 'Ready to accept new requests.' })
  @ApiServiceUnavailableResponse({ description: 'Shutdown has started and new requests are no longer accepted.' })
  ready() {
    if (this.shutdownState.isShuttingDown) {
      throw new ServiceUnavailableException('shutting down')
    }
    return { status: 'ok' }
  }
}
