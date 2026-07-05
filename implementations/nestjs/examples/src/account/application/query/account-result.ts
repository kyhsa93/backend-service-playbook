import { ApiProperty } from '@nestjs/swagger'

export class MoneyResult {
  @ApiProperty()
  public readonly amount: number

  @ApiProperty()
  public readonly currency: string
}

export class GetAccountResult {
  @ApiProperty()
  public readonly accountId: string

  @ApiProperty()
  public readonly ownerId: string

  @ApiProperty({ type: MoneyResult })
  public readonly balance: MoneyResult

  @ApiProperty()
  public readonly status: string

  @ApiProperty()
  public readonly createdAt: Date

  @ApiProperty()
  public readonly updatedAt: Date
}

export class TransactionSummaryResult {
  @ApiProperty()
  public readonly transactionId: string

  @ApiProperty()
  public readonly type: string

  @ApiProperty({ type: MoneyResult })
  public readonly amount: MoneyResult

  @ApiProperty()
  public readonly createdAt: Date
}

export class GetTransactionsResult {
  @ApiProperty({ type: [TransactionSummaryResult] })
  public readonly transactions: TransactionSummaryResult[]

  @ApiProperty()
  public readonly count: number
}
