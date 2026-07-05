import { ApiProperty } from '@nestjs/swagger'

import { MoneyResult } from '@/account/application/query/account-result'

export class WithdrawResponseBody {
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
