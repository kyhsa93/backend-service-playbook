import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class IssueCardRequestBody {
  @ApiProperty({ description: 'The account to link the new card to.' })
  @IsString()
  @MinLength(1)
  public readonly accountId: string

  @ApiProperty({ description: 'The card brand.', example: 'VISA' })
  @IsString()
  @MinLength(1)
  public readonly brand: string
}
