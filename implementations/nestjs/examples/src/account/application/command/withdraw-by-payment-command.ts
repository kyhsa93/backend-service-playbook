export class WithdrawByPaymentCommand {
  public readonly accountId: string
  public readonly amount: number
  // Payment BC의 paymentId. 멱등성 판단(Level 2 Ledger)의 키로 쓰인다.
  public readonly referenceId: string

  constructor(command: WithdrawByPaymentCommand) {
    Object.assign(this, command)
  }
}
