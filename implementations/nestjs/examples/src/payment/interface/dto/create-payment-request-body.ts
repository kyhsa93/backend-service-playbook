import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min, MinLength } from 'class-validator'

export class CreatePaymentRequestBody {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly cardId: string

  @ApiProperty({ minimum: 1 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number
}
