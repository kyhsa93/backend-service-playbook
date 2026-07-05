export class ReactivateAccountCommand {
  public readonly accountId: string
  public readonly requesterId: string

  constructor(command: ReactivateAccountCommand) {
    Object.assign(this, command)
  }
}
