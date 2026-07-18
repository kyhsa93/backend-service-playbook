import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsString, Min, MinLength } from 'class-validator'

export class CreatePaymentCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly cardId: string

  @ApiProperty({ minimum: 1 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  public readonly amount: number

  public readonly requesterId: string

  constructor(command: CreatePaymentCommand) {
    Object.assign(this, command)
  }
}
