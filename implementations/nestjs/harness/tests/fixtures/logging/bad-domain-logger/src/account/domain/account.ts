import { Logger } from '@nestjs/common'

export class Account {
  private readonly logger = new Logger(Account.name)
  public readonly accountId: string

  constructor(params: { accountId: string }) {
    this.accountId = params.accountId
  }

  public suspend(): void {
    this.logger.log('suspending account')
  }
}
