import { Controller, Get, ServiceUnavailableException } from '@nestjs/common'
import { ApiOkResponse, ApiServiceUnavailableResponse, ApiTags } from '@nestjs/swagger'
import { SkipThrottle } from '@nestjs/throttler'

import { ShutdownState } from '@/common/infrastructure/shutdown-state'

@Controller('health')
@ApiTags('Health')
@SkipThrottle()
export class HealthController {
  constructor(private readonly shutdownState: ShutdownState) {}

  @Get('live')
  @ApiOkResponse({ description: '프로세스가 살아있음 — 종료 중에도 200을 반환한다' })
  live() {
    return { status: 'ok' }
  }

  @Get('ready')
  @ApiOkResponse({ description: '새 요청을 수락할 준비가 됨' })
  @ApiServiceUnavailableResponse({ description: '종료 절차가 시작되어 새 요청을 받지 않음' })
  ready() {
    if (this.shutdownState.isShuttingDown) {
      throw new ServiceUnavailableException('shutting down')
    }
    return { status: 'ok' }
  }
}
