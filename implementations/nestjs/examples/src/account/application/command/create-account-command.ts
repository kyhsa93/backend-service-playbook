import { ApiProperty } from '@nestjs/swagger'
import { IsEmail, IsString, MinLength } from 'class-validator'

export class CreateAccountCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly currency: string

  @ApiProperty()
  @IsEmail()
  public readonly email: string

  public readonly requesterId: string

  constructor(command: CreateAccountCommand) {
    Object.assign(this, command)
  }
}
