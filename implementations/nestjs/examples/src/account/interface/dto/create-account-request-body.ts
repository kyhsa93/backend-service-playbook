import { ApiProperty } from '@nestjs/swagger'
import { IsEmail, IsString, MinLength } from 'class-validator'

export class CreateAccountRequestBody {
  @ApiProperty({ description: 'The ISO 4217 currency code the account is opened in.', example: 'KRW' })
  @IsString()
  @MinLength(1)
  public readonly currency: string

  @ApiProperty({ description: "The account owner's email address, used for notifications.", example: 'owner@example.com' })
  @IsEmail()
  public readonly email: string
}
