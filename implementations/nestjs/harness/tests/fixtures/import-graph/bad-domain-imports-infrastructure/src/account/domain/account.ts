import { AccountEntity } from '../infrastructure/account.entity'

export class Account {
  public toEntity(): AccountEntity {
    return new AccountEntity()
  }
}
