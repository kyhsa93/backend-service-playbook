import { Controller, Get, Req, UseGuards } from '@nestjs/common'
import { Request } from 'express'

import { AuthGuard } from '@/auth/auth.guard'

type AuthenticatedRequest = Request & { user: { userId: string } }

@Controller('orders')
@UseGuards(AuthGuard)
export class OrderController {
  @Get()
  public async getOrders(@Req() req: AuthenticatedRequest): Promise<{ orders: unknown[] }> {
    const requesterId = req.user.userId
    return { orders: [requesterId] }
  }
}
