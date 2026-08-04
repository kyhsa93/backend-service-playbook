export class CreateAccountHandler {
  public execute(): string {
    return process.env.ACCOUNT_PREFIX ?? 'ACC'
  }
}
