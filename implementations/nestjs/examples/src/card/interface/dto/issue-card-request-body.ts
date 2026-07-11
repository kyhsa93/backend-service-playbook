import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class IssueCardRequestBody {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly accountId: string

  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly brand: string
}
