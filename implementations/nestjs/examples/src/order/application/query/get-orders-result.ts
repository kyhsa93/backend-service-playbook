import { ApiProperty } from '@nestjs/swagger'

export class OrderSummaryItem {
  @ApiProperty()
  public readonly orderId: string

  @ApiProperty({ nullable: true, type: String })
  public readonly description: string | null

  @ApiProperty()
  public readonly status: string
}

export class GetOrdersResult {
  @ApiProperty({ type: [OrderSummaryItem] })
  public readonly orders: OrderSummaryItem[]

  @ApiProperty()
  public readonly totalCount: number
}
