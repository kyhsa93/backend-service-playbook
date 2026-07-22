import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class GetRefundsRequestParam {
  @ApiProperty({ description: 'The payment ID whose refunds are being listed.' })
  @IsString()
  @MinLength(1)
  public readonly paymentId: string
}
