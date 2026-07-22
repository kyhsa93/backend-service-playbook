import { Controller, Get } from '@nestjs/common'

import { Authenticated } from '@/auth/authenticated.decorator'
import { UserContextStore } from '@/common/user-context-store'

@Controller('orders')
@Authenticated()
export class OrderController {
  @Get()
  public async getOrders(): Promise<{ orders: unknown[] }> {
    const requesterId = UserContextStore.getRequesterId()
    return { orders: [requesterId] }
  }
}
