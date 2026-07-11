import { ApiProperty } from '@nestjs/swagger'
import { IsString } from 'class-validator'

export class GetCardRequestParam {
  @ApiProperty()
  @IsString()
  public readonly cardId: string
}
