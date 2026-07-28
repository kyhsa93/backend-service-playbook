import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsOptional, IsString, Min, MinLength } from 'class-validator'

export class WithdrawRequestBody {
  @ApiProperty({ description: 'The amount to withdraw, in the account\'s currency minor unit. Must be a positive integer.', minimum: 1, example: 10000 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number

  @ApiPropertyOptional({ description: 'The payee/merchant this withdrawal is for, e.g. for spending categorization. Optional.', example: 'Starbucks Gangnam' })
  @IsOptional()
  @IsString()
  @MinLength(1)
  public readonly merchantName?: string
}
