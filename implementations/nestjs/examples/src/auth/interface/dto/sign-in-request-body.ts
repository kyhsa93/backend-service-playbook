import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class SignInRequestBody {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly userId: string
}
