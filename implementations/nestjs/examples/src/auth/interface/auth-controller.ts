import { BadRequestException, Body, Controller, HttpCode, Logger, Post, UnauthorizedException } from '@nestjs/common'
import { ApiCreatedResponse, ApiTags } from '@nestjs/swagger'
import { CommandBus } from '@nestjs/cqrs'

import { generateErrorResponse } from '@/common/generate-error-response'
import { Public } from '@/auth/public.decorator'
import { SignInCommand } from '@/auth/application/command/sign-in-command'
import { SignUpCommand } from '@/auth/application/command/sign-up-command'
import { SignInRequestBody } from '@/auth/interface/dto/sign-in-request-body'
import { SignInResponseBody } from '@/auth/interface/dto/sign-in-response-body'
import { SignUpRequestBody } from '@/auth/interface/dto/sign-up-request-body'
import { AuthErrorMessage as ErrorMessage } from '@/auth/auth-error-message'

@Controller()
@ApiTags('Auth')
export class AuthController {
  private readonly logger = new Logger(AuthController.name)

  constructor(private readonly commandBus: CommandBus) {}

  @Public()
  @Post('/auth/sign-up')
  @HttpCode(201)
  public async signUp(@Body() body: SignUpRequestBody): Promise<void> {
    return this.commandBus.execute<SignUpCommand, void>(new SignUpCommand(body))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [ErrorMessage['이미 사용 중인 아이디입니다.'], BadRequestException, 'USER_ID_ALREADY_EXISTS']
        ])
      })
  }

  @Public()
  @Post('/auth/sign-in')
  @ApiCreatedResponse({ type: SignInResponseBody })
  public async signIn(@Body() body: SignInRequestBody): Promise<SignInResponseBody> {
    return this.commandBus.execute<SignInCommand, string>(new SignInCommand(body))
      .then((accessToken) => ({ accessToken }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [ErrorMessage['아이디 또는 비밀번호가 올바르지 않습니다.'], UnauthorizedException, 'INVALID_CREDENTIALS']
        ])
      })
  }
}
