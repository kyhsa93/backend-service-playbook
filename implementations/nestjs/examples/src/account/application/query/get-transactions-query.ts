import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, Max, Min } from 'class-validator'

export class GetTransactionsQuery {
  public readonly accountId: string
  public readonly requesterId: string

  @ApiProperty({ minimum: 0, default: 0 })
  @Type(() => Number)
  @IsInt()
  @Min(0)
  public readonly page: number

  @ApiProperty({ minimum: 1, maximum: 100, default: 20 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  public readonly take: number

  constructor(query: GetTransactionsQuery) {
    Object.assign(this, query)
  }
}
