import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { PasswordHasher } from '@/auth/application/service/password-hasher'
import { SignUpCommand } from '@/auth/application/command/sign-up-command'
import { Credential } from '@/auth/domain/credential'
import { CredentialRepository } from '@/auth/domain/credential-repository'
import { AuthErrorMessage as ErrorMessage } from '@/auth/auth-error-message'

@CommandHandler(SignUpCommand)
export class SignUpCommandHandler implements ICommandHandler<SignUpCommand> {
  constructor(
    private readonly credentialRepository: CredentialRepository,
    private readonly passwordHasher: PasswordHasher
  ) {}

  public async execute(command: SignUpCommand): Promise<void> {
    const existing = await this.credentialRepository
      .findCredentials({ userId: command.userId, take: 1, page: 0 })
      .then((r) => r.credentials.pop())
    if (existing) throw new Error(ErrorMessage['이미 사용 중인 아이디입니다.'])

    const passwordHash = await this.passwordHasher.hash(command.password)
    const credential = Credential.create({ userId: command.userId, passwordHash })
    await this.credentialRepository.saveCredential(credential)
  }
}
