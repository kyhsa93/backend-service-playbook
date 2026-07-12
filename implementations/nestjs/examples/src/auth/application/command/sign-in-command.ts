import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class SignInCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly userId: string

  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly password: string

  constructor(command: SignInCommand) {
    Object.assign(this, command)
  }
}
