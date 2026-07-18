import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger'

export class GetRefundResult {
  @ApiProperty()
  public readonly refundId: string

  @ApiProperty()
  public readonly paymentId: string

  @ApiProperty()
  public readonly amount: number

  @ApiProperty()
  public readonly reason: string

  @ApiProperty()
  public readonly status: string

  @ApiPropertyOptional()
  public readonly decisionNote?: string

  @ApiProperty()
  public readonly createdAt: Date
}

export class GetRefundsResult {
  @ApiProperty({ type: [GetRefundResult] })
  public readonly refunds: GetRefundResult[]

  @ApiProperty()
  public readonly count: number
}
