import { Test } from '@nestjs/testing'

import { CreateAccountCommandHandler } from '@/account/application/command/create-account-command-handler'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { AccountRepository } from '@/account/domain/account-repository'
import { OutboxRelay } from '@/account/application/event/outbox-relay'
import { TransactionManager } from '@/database/transaction-manager'

describe('CreateAccountCommandHandler', () => {
  let handler: CreateAccountCommandHandler
  let accountRepository: jest.Mocked<AccountRepository>
  let outboxRelay: jest.Mocked<OutboxRelay>

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
        },
        {
          provide: OutboxRelay,
          useValue: { processPending: jest.fn() }
        }
      ]
    }).compile()

    handler = module.get(CreateAccountCommandHandler)
    accountRepository = module.get(AccountRepository)
    outboxRelay = module.get(OutboxRelay)
  })

  it('execute_when_정상_입력_then_계좌를_저장하고_Outbox를_드레인한_뒤_계좌를_반환한다', async () => {
    const account = await handler.execute(
      new CreateAccountCommand({ requesterId: 'owner-1', email: 'owner1@example.com', currency: 'KRW' })
    )

    expect(account.ownerId).toBe('owner-1')
    expect(account.balance.amount).toBe(0)
    expect(accountRepository.saveAccount).toHaveBeenCalledWith(account)
    expect(outboxRelay.processPending).toHaveBeenCalled()
  })
})
