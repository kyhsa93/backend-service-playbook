import { ApiProperty } from '@nestjs/swagger'

export class MoneyResult {
  @ApiProperty({ description: 'The amount, in the currency\'s minor unit.' })
  public readonly amount: number

  @ApiProperty({ description: 'The ISO 4217 currency code.', example: 'KRW' })
  public readonly currency: string
}

export class GetAccountResult {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the account.' })
  public readonly accountId: string

  @ApiProperty({ description: 'The ID of the user who owns this account.' })
  public readonly ownerId: string

  @ApiProperty({ description: "The account owner's email address." })
  public readonly email: string

  @ApiProperty({ description: 'The current balance.', type: MoneyResult })
  public readonly balance: MoneyResult

  @ApiProperty({ description: 'The account status.', enum: ['ACTIVE', 'SUSPENDED', 'CLOSED'] })
  public readonly status: string

  @ApiProperty({ description: 'When the account was opened.' })
  public readonly createdAt: Date

  @ApiProperty({ description: 'When the account was last modified.' })
  public readonly updatedAt: Date
}

export class TransactionSummaryResult {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the transaction.' })
  public readonly transactionId: string

  @ApiProperty({ description: 'The transaction type.', enum: ['DEPOSIT', 'WITHDRAWAL', 'INTEREST'] })
  public readonly type: string

  @ApiProperty({ description: 'The transaction amount.', type: MoneyResult })
  public readonly amount: MoneyResult

  @ApiProperty({ description: 'When the transaction was recorded.' })
  public readonly createdAt: Date
}

export class GetTransactionsResult {
  @ApiProperty({ description: 'The transactions for the current page, newest first.', type: [TransactionSummaryResult] })
  public readonly transactions: TransactionSummaryResult[]

  @ApiProperty({ description: 'The total number of transactions across all pages.' })
  public readonly count: number
}
