import { ApiProperty } from '@nestjs/swagger'

export class SpendingAnalysisResult {
  @ApiProperty({ description: 'The analyzed month, in YYYY-MM form.' })
  public readonly analysisMonth: string

  @ApiProperty({ description: "The account's total withdrawal amount for the month." })
  public readonly totalAmount: number

  @ApiProperty({ description: 'The number of withdrawal transactions in the month.' })
  public readonly transactionCount: number

  @ApiProperty({ description: 'The average withdrawal amount per transaction.' })
  public readonly averageAmount: number

  @ApiProperty({ description: 'The percentage change in total withdrawal amount versus the previous month.' })
  public readonly changeFromPreviousMonth: number

  @ApiProperty({ description: 'A simple classification of the change.', enum: ['INCREASING', 'DECREASING', 'STABLE'] })
  public readonly trend: string

  @ApiProperty({ description: 'When this analysis was computed.' })
  public readonly createdAt: Date
}
