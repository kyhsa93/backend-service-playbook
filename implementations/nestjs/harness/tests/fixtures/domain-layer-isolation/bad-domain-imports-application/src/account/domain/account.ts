import { CreateAccountCommand } from '@/account/application/command/create-account-command'

export class Account {
  public readonly accountId: string

  constructor(command: CreateAccountCommand) {
    this.accountId = command.ownerId
  }
}
