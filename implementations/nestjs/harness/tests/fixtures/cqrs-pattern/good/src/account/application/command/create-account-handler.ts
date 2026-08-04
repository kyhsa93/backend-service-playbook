import { AccountRepository } from '../../domain/account-repository'

export class CreateAccountHandler {
  constructor(private readonly accountRepository: AccountRepository) {}
}
