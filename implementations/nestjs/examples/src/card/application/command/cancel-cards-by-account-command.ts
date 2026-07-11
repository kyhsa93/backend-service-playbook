export class CancelCardsByAccountCommand {
  public readonly accountId: string

  constructor(command: CancelCardsByAccountCommand) {
    Object.assign(this, command)
  }
}
