import { AccountRepository } from '../../domain/account-repository'

export class GetAccountHandler {
  constructor(private readonly accountRepository: AccountRepository) {}
}
