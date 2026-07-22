import { ApiProperty } from '@nestjs/swagger'
import { IsString } from 'class-validator'

export class GetPaymentRequestParam {
  @ApiProperty({ description: 'The payment ID.' })
  @IsString()
  public readonly paymentId: string
}
