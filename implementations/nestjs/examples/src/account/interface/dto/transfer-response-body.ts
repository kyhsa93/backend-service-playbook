import { ApiProperty } from '@nestjs/swagger'

import { MoneyResult } from '@/account/application/query/account-result'

export class TransferTransactionResult {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the transaction.' })
  public readonly transactionId: string

  @ApiProperty({ description: 'The account this transaction belongs to.' })
  public readonly accountId: string

  @ApiProperty({ description: 'The transaction type.', example: 'WITHDRAWAL' })
  public readonly type: string

  @ApiProperty({ description: 'The transaction amount.', type: MoneyResult })
  public readonly amount: MoneyResult

  @ApiProperty({ description: 'When the transaction was recorded.' })
  public readonly createdAt: Date
}

export class TransferResponseBody {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the transfer.' })
  public readonly transferId: string

  @ApiProperty({ description: 'The withdrawal recorded on the source account.', type: TransferTransactionResult })
  public readonly sourceTransaction: TransferTransactionResult

  @ApiProperty({ description: 'The deposit recorded on the target account.', type: TransferTransactionResult })
  public readonly targetTransaction: TransferTransactionResult
}
