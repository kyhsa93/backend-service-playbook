import { ApiProperty } from '@nestjs/swagger'
import { IsISO8601 } from 'class-validator'

export class GetSpendingAnalysisQuery {
  public readonly accountId: string
  public readonly requesterId: string

  // Validated as a full ISO 8601 date (YYYY-MM-01) rather than a bare YYYY-MM, so
  // class-validator can check it with an existing decorator — the day-of-month is discarded,
  // only the year/month are used.
  @ApiProperty({ description: 'The month to look up, as YYYY-MM-01 (the day is ignored).', example: '2026-07-01' })
  @IsISO8601({ strict: true })
  public readonly month: string

  constructor(query: Pick<GetSpendingAnalysisQuery, 'accountId' | 'requesterId' | 'month'>) {
    Object.assign(this, query)
  }

  public get analysisMonth(): string {
    return this.month.slice(0, 7)
  }
}
