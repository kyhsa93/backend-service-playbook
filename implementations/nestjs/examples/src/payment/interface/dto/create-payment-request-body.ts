import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min, MinLength } from 'class-validator'

export class CreatePaymentRequestBody {
  @ApiProperty({ description: 'The card to charge.' })
  @IsString()
  @MinLength(1)
  public readonly cardId: string

  @ApiProperty({ description: 'The amount to charge, in the account\'s currency minor unit. Must be a positive integer.', minimum: 1, example: 5000 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number
}
