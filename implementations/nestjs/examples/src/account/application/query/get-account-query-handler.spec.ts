import { Test } from '@nestjs/testing'

import { GetAccountQueryHandler } from '@/account/application/query/get-account-query-handler'
import { GetAccountQuery } from '@/account/application/query/get-account-query'
import { AccountQuery } from '@/account/application/query/account-query'

describe('GetAccountQueryHandler', () => {
  let handler: GetAccountQueryHandler
  let accountQuery: jest.Mocked<AccountQuery>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        GetAccountQueryHandler,
        {
          provide: AccountQuery,
          useValue: { getAccount: jest.fn(), getTransactions: jest.fn() }
        }
      ]
    }).compile()

    handler = module.get(GetAccountQueryHandler)
    accountQuery = module.get(AccountQuery)
  })

  it('execute_when_called_then_queries_AccountQuery_passing_accountId_and_ownerId_through_as_is', async () => {
    const expected = {
      accountId: 'account-1',
      ownerId: 'owner-1',
      email: 'owner1@example.com',
      balance: { amount: 0, currency: 'KRW' },
      status: 'ACTIVE',
      createdAt: new Date(),
      updatedAt: new Date()
    }
    accountQuery.getAccount.mockResolvedValue(expected as never)

    const result = await handler.execute(new GetAccountQuery({ accountId: 'account-1', requesterId: 'owner-1' }))

    expect(accountQuery.getAccount).toHaveBeenCalledWith({ accountId: 'account-1', ownerId: 'owner-1' })
    expect(result).toBe(expected)
  })
})
