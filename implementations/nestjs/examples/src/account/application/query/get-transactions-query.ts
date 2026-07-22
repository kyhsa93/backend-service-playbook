import { ApiPropertyOptional } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsOptional, Max, Min } from 'class-validator'

export class GetTransactionsQuery {
  public readonly accountId: string
  public readonly requesterId: string

  @ApiPropertyOptional({ description: 'The zero-based page number.', minimum: 0, default: 0 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(0)
  public readonly page: number = 0

  @ApiPropertyOptional({ description: 'The number of transactions per page.', minimum: 1, maximum: 100, default: 20 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  public readonly take: number = 20

  constructor(query: GetTransactionsQuery) {
    Object.assign(this, query)
  }
}
