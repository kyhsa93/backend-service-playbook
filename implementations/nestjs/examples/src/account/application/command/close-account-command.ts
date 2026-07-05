export class CloseAccountCommand {
  public readonly accountId: string
  public readonly requesterId: string

  constructor(command: CloseAccountCommand) {
    Object.assign(this, command)
  }
}
