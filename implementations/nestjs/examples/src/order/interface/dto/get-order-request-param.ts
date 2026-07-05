import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class GetOrderRequestParam {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly orderId: string
}
