import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class CancelPaymentRequestBody {
  @ApiProperty({ description: 'Why the payment is being cancelled.' })
  @IsString()
  @MinLength(1)
  public readonly reason: string
}
