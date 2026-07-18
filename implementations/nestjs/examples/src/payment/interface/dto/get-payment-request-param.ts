import { ApiProperty } from '@nestjs/swagger'
import { IsString } from 'class-validator'

export class GetPaymentRequestParam {
  @ApiProperty()
  @IsString()
  public readonly paymentId: string
}
