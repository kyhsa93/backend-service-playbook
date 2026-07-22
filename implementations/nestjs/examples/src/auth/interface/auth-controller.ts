import { BadRequestException, Body, Controller, HttpCode, Logger, Post, UnauthorizedException } from '@nestjs/common'
import { ApiBadRequestResponse, ApiCreatedResponse, ApiOperation, ApiTags, ApiUnauthorizedResponse } from '@nestjs/swagger'
import { CommandBus } from '@nestjs/cqrs'

import { generateErrorResponse } from '@/common/generate-error-response'
import { ErrorResponseBody } from '@/common/interface/dto/error-response-body'
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
  @ApiOperation({
    operationId: 'signUp',
    summary: 'Register a new user',
    description: 'Creates a new credential (username + password) that can then be used to sign in.'
  })
  @ApiCreatedResponse({ description: 'The user was registered.' })
  @ApiBadRequestResponse({
    description: 'One of: the username is already in use (`USER_ID_ALREADY_EXISTS`), or request validation failed (`VALIDATION_FAILED`) — e.g. a password shorter than 8 characters.',
    type: ErrorResponseBody
  })
  public async signUp(@Body() body: SignUpRequestBody): Promise<void> {
    return this.commandBus.execute<SignUpCommand, void>(new SignUpCommand(body))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [ErrorMessage['This username is already in use.'], BadRequestException, 'USER_ID_ALREADY_EXISTS']
        ])
      })
  }

  @Public()
  @Post('/auth/sign-in')
  @ApiOperation({
    operationId: 'signIn',
    summary: 'Sign in',
    description: 'Verifies the username and password, and issues a bearer access token to use as `Authorization: Bearer <token>` on every other endpoint.'
  })
  @ApiCreatedResponse({ description: 'The credentials were valid; an access token was issued.', type: SignInResponseBody })
  @ApiBadRequestResponse({ description: 'Request validation failed (`VALIDATION_FAILED`) — e.g. a missing username or password.', type: ErrorResponseBody })
  @ApiUnauthorizedResponse({
    description: 'The username or password is incorrect (`INVALID_CREDENTIALS`). The same message and code are returned whether the username doesn\'t exist or the password is wrong, to avoid leaking which one was wrong.',
    type: ErrorResponseBody
  })
  public async signIn(@Body() body: SignInRequestBody): Promise<SignInResponseBody> {
    return this.commandBus.execute<SignInCommand, string>(new SignInCommand(body))
      .then((accessToken) => ({ accessToken }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [ErrorMessage['Incorrect username or password.'], UnauthorizedException, 'INVALID_CREDENTIALS']
        ])
      })
  }
}
