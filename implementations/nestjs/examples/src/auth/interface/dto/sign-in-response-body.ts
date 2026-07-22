import { ApiProperty } from '@nestjs/swagger'

export class SignInResponseBody {
  @ApiProperty({ description: 'A JWT bearer access token — send it as `Authorization: Bearer <token>` on every other endpoint.' })
  public readonly accessToken: string
}
