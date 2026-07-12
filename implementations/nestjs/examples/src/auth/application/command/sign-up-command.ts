import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class SignUpCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly userId: string

  @ApiProperty()
  @IsString()
  @MinLength(8)
  public readonly password: string

  constructor(command: SignUpCommand) {
    Object.assign(this, command)
  }
}
