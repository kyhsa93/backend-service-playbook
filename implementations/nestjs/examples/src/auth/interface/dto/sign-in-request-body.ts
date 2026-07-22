import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class SignInRequestBody {
  @ApiProperty({ description: 'The username.' })
  @IsString()
  @MinLength(1)
  public readonly userId: string

  @ApiProperty({ description: 'The password.' })
  @IsString()
  @MinLength(1)
  public readonly password: string
}
