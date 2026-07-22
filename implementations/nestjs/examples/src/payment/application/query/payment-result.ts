import { ApiProperty } from '@nestjs/swagger'

export class GetPaymentResult {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the payment.' })
  public readonly paymentId: string

  @ApiProperty({ description: 'The card charged for this payment.' })
  public readonly cardId: string

  @ApiProperty({ description: 'The account debited for this payment.' })
  public readonly accountId: string

  @ApiProperty({ description: 'The ID of the user who made this payment.' })
  public readonly ownerId: string

  @ApiProperty({ description: "The charged amount, in the account's currency minor unit." })
  public readonly amount: number

  @ApiProperty({ description: 'The payment status.', enum: ['PENDING', 'COMPLETED', 'FAILED', 'CANCELLED'] })
  public readonly status: string

  @ApiProperty({ description: 'When the payment was created.' })
  public readonly createdAt: Date
}

export class GetPaymentsResult {
  @ApiProperty({ description: 'The payments for the current page, newest first.', type: [GetPaymentResult] })
  public readonly payments: GetPaymentResult[]

  @ApiProperty({ description: 'The total number of payments across all pages.' })
  public readonly count: number
}
