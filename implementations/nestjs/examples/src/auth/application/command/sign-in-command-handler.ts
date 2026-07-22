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
    // Respond with the same message for a nonexistent ID / a password mismatch — so an existing ID can't be guessed
    if (!credential) throw new Error(ErrorMessage['아이디 또는 비밀번호가 올바르지 않습니다.'])

    const isValid = await this.passwordHasher.verify(command.password, credential.passwordHash)
    if (!isValid) throw new Error(ErrorMessage['아이디 또는 비밀번호가 올바르지 않습니다.'])

    return this.authService.sign({ userId: credential.userId })
  }
}
