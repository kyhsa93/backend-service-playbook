import { Controller, Get, ServiceUnavailableException } from '@nestjs/common'
import { ApiOkResponse, ApiServiceUnavailableResponse, ApiTags } from '@nestjs/swagger'
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
  @ApiOkResponse({ description: 'The process is alive — returns 200 even while shutting down' })
  live() {
    return { status: 'ok' }
  }

  @Get('ready')
  @ApiOkResponse({ description: 'Ready to accept new requests' })
  @ApiServiceUnavailableResponse({ description: 'Shutdown has started and new requests are no longer accepted' })
  ready() {
    if (this.shutdownState.isShuttingDown) {
      throw new ServiceUnavailableException('shutting down')
    }
    return { status: 'ok' }
  }
}
