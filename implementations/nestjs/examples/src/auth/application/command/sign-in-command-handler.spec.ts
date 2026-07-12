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

  it('execute_when_아이디와_비밀번호가_일치하면_then_액세스_토큰을_발급한다', async () => {
    const credential = Credential.create({ userId: 'owner-1', passwordHash: 'hashed-password' })
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [credential], count: 1 })
    passwordHasher.verify.mockResolvedValue(true)
    authService.sign.mockResolvedValue('access-token')

    const accessToken = await handler.execute(new SignInCommand({ userId: 'owner-1', password: 'plain-password' }))

    expect(passwordHasher.verify).toHaveBeenCalledWith('plain-password', 'hashed-password')
    expect(authService.sign).toHaveBeenCalledWith({ userId: 'owner-1' })
    expect(accessToken).toBe('access-token')
  })

  it('execute_when_존재하지_않는_아이디면_then_에러를_throw한다', async () => {
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [], count: 0 })

    await expect(
      handler.execute(new SignInCommand({ userId: 'no-such-user', password: 'plain-password' }))
    ).rejects.toThrow('아이디 또는 비밀번호가 올바르지 않습니다.')
    expect(authService.sign).not.toHaveBeenCalled()
  })

  it('execute_when_비밀번호가_틀리면_then_에러를_throw한다', async () => {
    const credential = Credential.create({ userId: 'owner-1', passwordHash: 'hashed-password' })
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [credential], count: 1 })
    passwordHasher.verify.mockResolvedValue(false)

    await expect(
      handler.execute(new SignInCommand({ userId: 'owner-1', password: 'wrong-password' }))
    ).rejects.toThrow('아이디 또는 비밀번호가 올바르지 않습니다.')
    expect(authService.sign).not.toHaveBeenCalled()
  })
})
