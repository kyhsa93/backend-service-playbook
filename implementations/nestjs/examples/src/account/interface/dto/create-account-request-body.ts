import { ApiProperty } from '@nestjs/swagger'
import { IsEmail, IsString, MinLength } from 'class-validator'

export class CreateAccountRequestBody {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly currency: string

  @ApiProperty()
  @IsEmail()
  public readonly email: string
}
