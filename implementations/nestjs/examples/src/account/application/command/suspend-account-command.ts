export class SuspendAccountCommand {
  public readonly accountId: string
  public readonly requesterId: string

  constructor(command: SuspendAccountCommand) {
    Object.assign(this, command)
  }
}
