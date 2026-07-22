import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min, MinLength } from 'class-validator'

export class RequestRefundRequestBody {
  @ApiProperty({ description: 'The amount to refund, in the payment\'s currency minor unit. Must be a positive integer and cannot exceed the original payment amount.', minimum: 1, example: 5000 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number

  @ApiProperty({ description: 'Why the refund is being requested.' })
  @IsString()
  @MinLength(1)
  public readonly reason: string
}
