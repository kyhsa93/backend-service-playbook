import { ApiProperty } from '@nestjs/swagger'

import { MoneyResult } from '@/account/application/query/account-result'

export class WithdrawResponseBody {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the transaction.' })
  public readonly transactionId: string

  @ApiProperty({ description: 'The account this transaction belongs to.' })
  public readonly accountId: string

  @ApiProperty({ description: 'The transaction type.', example: 'WITHDRAWAL' })
  public readonly type: string

  @ApiProperty({ description: 'The withdrawn amount.', type: MoneyResult })
  public readonly amount: MoneyResult

  @ApiProperty({ description: 'When the transaction was recorded.' })
  public readonly createdAt: Date
}
