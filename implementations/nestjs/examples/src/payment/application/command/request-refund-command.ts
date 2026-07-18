import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min, MinLength } from 'class-validator'

export class RequestRefundCommand {
  public readonly paymentId: string

  @ApiProperty({ minimum: 1 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number

  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly reason: string

  public readonly requesterId: string

  constructor(command: RequestRefundCommand) {
    Object.assign(this, command)
  }
}
