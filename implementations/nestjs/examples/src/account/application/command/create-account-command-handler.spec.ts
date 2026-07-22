import { Test } from '@nestjs/testing'

import { CreateAccountCommandHandler } from '@/account/application/command/create-account-command-handler'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { TransactionManager } from '@/database/transaction-manager'

describe('CreateAccountCommandHandler', () => {
  let handler: CreateAccountCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        CreateAccountCommandHandler,
        {
          provide: AccountRepository,
          useValue: { findAccounts: jest.fn(), saveAccount: jest.fn() }
        },
        {
          provide: TransactionManager,
          useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() }
        }
      ]
    }).compile()

    handler = module.get(CreateAccountCommandHandler)
    accountRepository = module.get(AccountRepository)
  })

  it('execute_when_valid_input_then_saves_the_account_and_returns_it', async () => {
    const account = await handler.execute(
      new CreateAccountCommand({ requesterId: 'owner-1', email: 'owner1@example.com', currency: 'KRW' })
    )

    expect(account.ownerId).toBe('owner-1')
    expect(account.balance.amount).toBe(0)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(account)
  })
})
