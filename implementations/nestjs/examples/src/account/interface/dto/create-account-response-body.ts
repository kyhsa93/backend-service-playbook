import { ApiProperty } from '@nestjs/swagger'

import { MoneyResult } from '@/account/application/query/account-result'

export class CreateAccountResponseBody {
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
}
