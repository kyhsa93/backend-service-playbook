import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger'

export class GetRefundResult {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the refund.' })
  public readonly refundId: string

  @ApiProperty({ description: 'The payment this refund was requested against.' })
  public readonly paymentId: string

  @ApiProperty({ description: "The requested refund amount, in the payment's currency minor unit." })
  public readonly amount: number

  @ApiProperty({ description: 'Why the refund was requested.' })
  public readonly reason: string

  @ApiProperty({ description: 'The refund status.', enum: ['REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED'] })
  public readonly status: string

  @ApiPropertyOptional({ description: 'Why the refund was approved or rejected, set once the eligibility decision is made.' })
  public readonly decisionNote?: string

  @ApiProperty({ description: 'When the refund was requested.' })
  public readonly createdAt: Date
}

export class GetRefundsResult {
  @ApiProperty({ description: 'The refunds for the current page, newest first.', type: [GetRefundResult] })
  public readonly refunds: GetRefundResult[]

  @ApiProperty({ description: 'The total number of refunds across all pages.' })
  public readonly count: number
}
