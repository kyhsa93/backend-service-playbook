import { ApiProperty } from '@nestjs/swagger'

export class SignInResponseBody {
  @ApiProperty()
  public readonly accessToken: string
}
