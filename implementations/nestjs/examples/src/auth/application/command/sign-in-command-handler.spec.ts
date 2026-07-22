import { Test } from '@nestjs/testing'

import { AuthService } from '@/auth/auth-service'
import { SignInCommandHandler } from '@/auth/application/command/sign-in-command-handler'
import { SignInCommand } from '@/auth/application/command/sign-in-command'
import { PasswordHasher } from '@/auth/application/service/password-hasher'
import { Credential } from '@/auth/domain/credential'
import { CredentialRepository } from '@/auth/domain/credential-repository'

describe('SignInCommandHandler', () => {
  let handler: SignInCommandHandler
  let credentialRepository: jest.Mocked<CredentialRepository>
  let passwordHasher: jest.Mocked<PasswordHasher>
  let authService: jest.Mocked<AuthService>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        SignInCommandHandler,
        {
          provide: CredentialRepository,
          useValue: { findCredentials: jest.fn(), saveCredential: jest.fn() }
        },
        {
          provide: PasswordHasher,
          useValue: { hash: jest.fn(), verify: jest.fn() }
        },
        {
          provide: AuthService,
          useValue: { sign: jest.fn(), verify: jest.fn() }
        }
      ]
    }).compile()

    handler = module.get(SignInCommandHandler)
    credentialRepository = module.get(CredentialRepository)
    passwordHasher = module.get(PasswordHasher)
    authService = module.get(AuthService)
  })

  it('execute_when_username_and_password_match_then_issues_an_access_token', async () => {
    const credential = Credential.create({ userId: 'owner-1', passwordHash: 'hashed-password' })
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [credential], count: 1 })
    passwordHasher.verify.mockResolvedValue(true)
    authService.sign.mockResolvedValue('access-token')

    const accessToken = await handler.execute(new SignInCommand({ userId: 'owner-1', password: 'plain-password' }))

    expect(passwordHasher.verify).toHaveBeenCalledWith('plain-password', 'hashed-password')
    expect(authService.sign).toHaveBeenCalledWith({ userId: 'owner-1' })
    expect(accessToken).toBe('access-token')
  })

  it('execute_when_the_username_does_not_exist_then_throws', async () => {
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [], count: 0 })

    await expect(
      handler.execute(new SignInCommand({ userId: 'no-such-user', password: 'plain-password' }))
    ).rejects.toThrow('Incorrect username or password.')
    expect(authService.sign).not.toHaveBeenCalled()
  })

  it('execute_when_the_password_is_wrong_then_throws', async () => {
    const credential = Credential.create({ userId: 'owner-1', passwordHash: 'hashed-password' })
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [credential], count: 1 })
    passwordHasher.verify.mockResolvedValue(false)

    await expect(
      handler.execute(new SignInCommand({ userId: 'owner-1', password: 'wrong-password' }))
    ).rejects.toThrow('Incorrect username or password.')
    expect(authService.sign).not.toHaveBeenCalled()
  })
})
