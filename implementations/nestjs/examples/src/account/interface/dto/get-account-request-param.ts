import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class GetAccountRequestParam {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly accountId: string
}
