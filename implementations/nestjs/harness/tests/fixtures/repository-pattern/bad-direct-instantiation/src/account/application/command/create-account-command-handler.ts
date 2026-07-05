import { AccountRepositoryImpl } from '@/account/infrastructure/account-repository-impl'

export class CreateAccountCommandHandler {
  public async execute(command: { ownerId: string }): Promise<void> {
    const accountRepository = new AccountRepositoryImpl()
    await accountRepository.saveAccount({ ownerId: command.ownerId })
  }
}
