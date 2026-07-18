import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min, MinLength } from 'class-validator'

export class RequestRefundRequestBody {
  @ApiProperty({ minimum: 1 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number

  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly reason: string
}
