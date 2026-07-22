import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min } from 'class-validator'

export class TransferRequestBody {
  @ApiProperty({ description: 'The account ID to transfer money into.' })
  @IsString()
  public readonly targetAccountId: string

  @ApiProperty({ description: 'The amount to transfer, in the account\'s currency minor unit. Must be a positive integer.', minimum: 1, example: 10000 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number
}
