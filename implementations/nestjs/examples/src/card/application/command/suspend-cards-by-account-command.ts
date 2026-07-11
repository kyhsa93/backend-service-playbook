export class SuspendCardsByAccountCommand {
  public readonly accountId: string

  constructor(command: SuspendCardsByAccountCommand) {
    Object.assign(this, command)
  }
}
