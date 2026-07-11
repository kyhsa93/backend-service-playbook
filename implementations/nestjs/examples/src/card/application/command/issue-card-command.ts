export class IssueCardCommand {
  public readonly accountId: string
  public readonly brand: string
  public readonly requesterId: string

  constructor(command: IssueCardCommand) {
    Object.assign(this, command)
  }
}
