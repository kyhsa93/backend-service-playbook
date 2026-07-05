import { ApiProperty } from '@nestjs/swagger'

export class GetOrderResult {
  @ApiProperty()
  public readonly orderId: string

  @ApiProperty()
  public readonly status: string

  @ApiProperty()
  public readonly totalAmount: number
}
