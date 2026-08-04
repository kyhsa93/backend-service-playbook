import { Account } from '../../domain/account'

export class CreateAccountHandler {
  public execute(): Account {
    return new Account('account-1')
  }
}
