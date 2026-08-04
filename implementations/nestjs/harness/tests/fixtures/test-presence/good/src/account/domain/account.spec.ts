import { Account } from './account'

describe('Account', () => {
  it('keeps its id', () => {
    expect(new Account('account-1').accountId).toBe('account-1')
  })
})
