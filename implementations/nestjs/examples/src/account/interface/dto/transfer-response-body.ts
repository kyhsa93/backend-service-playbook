import { ApiProperty } from '@nestjs/swagger'

import { MoneyResult } from '@/account/application/query/account-result'

export class TransferTransactionResult {
  @ApiProperty()
  public readonly transactionId: string

  @ApiProperty()
  public readonly accountId: string

  @ApiProperty()
  public readonly type: string

  @ApiProperty({ type: MoneyResult })
  public readonly amount: MoneyResult

  @ApiProperty()
  public readonly createdAt: Date
}

export class TransferResponseBody {
  @ApiProperty()
  public readonly transferId: string

  @ApiProperty({ type: TransferTransactionResult })
  public readonly sourceTransaction: TransferTransactionResult

  @ApiProperty({ type: TransferTransactionResult })
  public readonly targetTransaction: TransferTransactionResult
}
