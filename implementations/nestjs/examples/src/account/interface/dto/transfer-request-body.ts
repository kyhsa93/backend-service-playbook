import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min } from 'class-validator'

export class TransferRequestBody {
  @ApiProperty()
  @IsString()
  public readonly targetAccountId: string

  @ApiProperty({ minimum: 1 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number
}
