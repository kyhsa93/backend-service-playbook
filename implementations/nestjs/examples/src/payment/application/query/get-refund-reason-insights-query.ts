import { ApiPropertyOptional } from '@nestjs/swagger'
import { IsISO8601, IsOptional } from 'class-validator'

export class GetRefundReasonInsightsQuery {
  @ApiPropertyOptional({ description: 'Only count refunds requested on/after this date (inclusive), as YYYY-MM-DD.', example: '2026-07-01' })
  @IsOptional()
  @IsISO8601({ strict: true })
  public readonly fromDate?: string

  @ApiPropertyOptional({ description: 'Only count refunds requested on/before this date (inclusive), as YYYY-MM-DD.', example: '2026-07-31' })
  @IsOptional()
  @IsISO8601({ strict: true })
  public readonly toDate?: string

  constructor(query: Pick<GetRefundReasonInsightsQuery, 'fromDate' | 'toDate'>) {
    Object.assign(this, query)
  }
}
