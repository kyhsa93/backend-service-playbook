import { ApiPropertyOptional } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsIn, IsInt, IsISO8601, IsOptional, Max, Min } from 'class-validator'

import { TransactionType } from '@/account/domain/transaction'

const TRANSACTION_TYPES: TransactionType[] = ['DEPOSIT', 'WITHDRAWAL', 'INTEREST']

export class GetTransactionsQuery {
  public readonly accountId: string
  public readonly requesterId: string

  @ApiPropertyOptional({ description: 'Only include transactions of this type.', enum: TRANSACTION_TYPES })
  @IsOptional()
  @IsIn(TRANSACTION_TYPES)
  public readonly type?: TransactionType

  @ApiPropertyOptional({ description: 'Only include transactions on or after this date (ISO 8601, e.g. 2026-07-01).' })
  @IsOptional()
  @IsISO8601({ strict: true })
  public readonly fromDate?: string

  @ApiPropertyOptional({ description: 'Only include transactions on or before this date (ISO 8601, e.g. 2026-07-31).' })
  @IsOptional()
  @IsISO8601({ strict: true })
  public readonly toDate?: string

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
