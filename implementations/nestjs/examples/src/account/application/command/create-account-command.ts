import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class CreateAccountCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly currency: string

  public readonly requesterId: string

  constructor(command: CreateAccountCommand) {
    Object.assign(this, command)
  }
}
