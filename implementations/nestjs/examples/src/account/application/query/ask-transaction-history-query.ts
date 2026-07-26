import { ApiProperty } from '@nestjs/swagger'
import { IsNotEmpty, IsString, MaxLength } from 'class-validator'

export class AskTransactionHistoryQuery {
  public readonly accountId: string
  public readonly requesterId: string

  @ApiProperty({ description: 'A free-text question about this account\'s transaction history.', example: 'How much did I deposit this month?' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(500)
  public readonly question: string

  constructor(query: AskTransactionHistoryQuery) {
    Object.assign(this, query)
  }
}
