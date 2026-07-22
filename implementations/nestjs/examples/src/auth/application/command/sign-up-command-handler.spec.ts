import { Test } from '@nestjs/testing'

import { SignUpCommandHandler } from '@/auth/application/command/sign-up-command-handler'
import { SignUpCommand } from '@/auth/application/command/sign-up-command'
import { PasswordHasher } from '@/auth/application/service/password-hasher'
import { Credential } from '@/auth/domain/credential'
import { CredentialRepository } from '@/auth/domain/credential-repository'

describe('SignUpCommandHandler', () => {
  let handler: SignUpCommandHandler
  let credentialRepository: jest.Mocked<CredentialRepository>
  let passwordHasher: jest.Mocked<PasswordHasher>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        SignUpCommandHandler,
        {
          provide: CredentialRepository,
          useValue: { findCredentials: jest.fn(), saveCredential: jest.fn() }
        },
        {
          provide: PasswordHasher,
          useValue: { hash: jest.fn(), verify: jest.fn() }
        }
      ]
    }).compile()

    handler = module.get(SignUpCommandHandler)
    credentialRepository = module.get(CredentialRepository)
    passwordHasher = module.get(PasswordHasher)
  })

  it('execute_when_the_username_is_new_then_hashes_the_password_and_saves', async () => {
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [], count: 0 })
    passwordHasher.hash.mockResolvedValue('hashed-password')

    await handler.execute(new SignUpCommand({ userId: 'owner-1', password: 'plain-password' }))

    expect(passwordHasher.hash).toHaveBeenCalledWith('plain-password')
    expect(credentialRepository.saveCredential).toHaveBeenCalledWith(
      expect.objectContaining({ userId: 'owner-1', passwordHash: 'hashed-password' })
    )
  })

  it('execute_when_the_username_already_exists_then_throws_and_does_not_save', async () => {
    const existing = Credential.create({ userId: 'owner-1', passwordHash: 'existing-hash' })
    credentialRepository.findCredentials.mockResolvedValue({ credentials: [existing], count: 1 })

    await expect(
      handler.execute(new SignUpCommand({ userId: 'owner-1', password: 'plain-password' }))
    ).rejects.toThrow('This username is already in use.')
    expect(credentialRepository.saveCredential).not.toHaveBeenCalled()
  })
})
