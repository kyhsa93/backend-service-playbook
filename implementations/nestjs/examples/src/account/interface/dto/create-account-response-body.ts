import { ApiProperty } from '@nestjs/swagger'

import { MoneyResult } from '@/account/application/query/account-result'

export class CreateAccountResponseBody {
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
}
