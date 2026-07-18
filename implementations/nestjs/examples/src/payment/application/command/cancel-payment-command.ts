import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class CancelPaymentCommand {
  public readonly paymentId: string

  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly reason: string

  public readonly requesterId: string

  constructor(command: CancelPaymentCommand) {
    Object.assign(this, command)
  }
}
