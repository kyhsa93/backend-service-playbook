import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { AuthService } from '@/auth/auth-service'
import { PasswordHasher } from '@/auth/application/service/password-hasher'
import { SignInCommand } from '@/auth/application/command/sign-in-command'
import { CredentialRepository } from '@/auth/domain/credential-repository'
import { AuthErrorMessage as ErrorMessage } from '@/auth/auth-error-message'

@CommandHandler(SignInCommand)
export class SignInCommandHandler implements ICommandHandler<SignInCommand, string> {
  constructor(
    private readonly credentialRepository: CredentialRepository,
    private readonly passwordHasher: PasswordHasher,
    private readonly authService: AuthService
  ) {}

  public async execute(command: SignInCommand): Promise<string> {
    const credential = await this.credentialRepository
      .findCredentials({ userId: command.userId, take: 1, page: 0 })
      .then((r) => r.credentials.pop())
    // 아이디 미존재/비밀번호 불일치를 동일한 메시지로 응답 — 존재하는 아이디를 추측 가능하게 만들지 않기 위함
    if (!credential) throw new Error(ErrorMessage['아이디 또는 비밀번호가 올바르지 않습니다.'])

    const isValid = await this.passwordHasher.verify(command.password, credential.passwordHash)
    if (!isValid) throw new Error(ErrorMessage['아이디 또는 비밀번호가 올바르지 않습니다.'])

    return this.authService.sign({ userId: credential.userId })
  }
}
