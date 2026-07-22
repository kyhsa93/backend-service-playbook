import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class SignUpRequestBody {
  @ApiProperty({ description: 'The desired username. Must be unique.' })
  @IsString()
  @MinLength(1)
  public readonly userId: string

  @ApiProperty({ description: 'The account password. Must be at least 8 characters.', minLength: 8 })
  @IsString()
  @MinLength(8)
  public readonly password: string
}
