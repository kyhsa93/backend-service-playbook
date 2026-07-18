import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class CancelPaymentRequestBody {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly reason: string
}
