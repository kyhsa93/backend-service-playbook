import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, Min } from 'class-validator'

export class WithdrawRequestBody {
  @ApiProperty({ description: 'The amount to withdraw, in the account\'s currency minor unit. Must be a positive integer.', minimum: 1, example: 10000 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number
}
