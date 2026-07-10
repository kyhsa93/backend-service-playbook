import { Body, Controller, Post } from '@nestjs/common'
import { ApiCreatedResponse, ApiTags } from '@nestjs/swagger'

import { AuthService } from '@/auth/auth-service'
import { SignInRequestBody } from '@/auth/interface/dto/sign-in-request-body'
import { SignInResponseBody } from '@/auth/interface/dto/sign-in-response-body'

@Controller()
@ApiTags('Auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Post('/auth/sign-in')
  @ApiCreatedResponse({ type: SignInResponseBody })
  public async signIn(@Body() body: SignInRequestBody): Promise<SignInResponseBody> {
    const accessToken = await this.authService.sign({ userId: body.userId })
    return { accessToken }
  }
}
