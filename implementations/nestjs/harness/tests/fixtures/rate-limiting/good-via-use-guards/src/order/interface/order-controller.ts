import { Controller, Post, UseGuards } from '@nestjs/common'
import { ThrottlerGuard } from '@nestjs/throttler'

@Controller('orders')
@UseGuards(ThrottlerGuard)
export class OrderController {
  @Post()
  public create(): void {
    // ...
  }
}
