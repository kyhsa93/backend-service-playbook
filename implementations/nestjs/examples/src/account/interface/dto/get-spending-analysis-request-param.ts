import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class GetSpendingAnalysisRequestParam {
  @ApiProperty({ description: 'The account ID.' })
  @IsString()
  @MinLength(1)
  public readonly accountId: string
}
