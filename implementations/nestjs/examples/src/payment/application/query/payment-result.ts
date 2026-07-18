import { ApiProperty } from '@nestjs/swagger'

export class GetPaymentResult {
  @ApiProperty()
  public readonly paymentId: string

  @ApiProperty()
  public readonly cardId: string

  @ApiProperty()
  public readonly accountId: string

  @ApiProperty()
  public readonly ownerId: string

  @ApiProperty()
  public readonly amount: number

  @ApiProperty()
  public readonly status: string

  @ApiProperty()
  public readonly createdAt: Date
}

export class GetPaymentsResult {
  @ApiProperty({ type: [GetPaymentResult] })
  public readonly payments: GetPaymentResult[]

  @ApiProperty()
  public readonly count: number
}
