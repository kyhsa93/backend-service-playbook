import { ApiProperty } from '@nestjs/swagger'
import { IsString } from 'class-validator'

export class GetCardRequestParam {
  @ApiProperty({ description: 'The card ID.' })
  @IsString()
  public readonly cardId: string
}
