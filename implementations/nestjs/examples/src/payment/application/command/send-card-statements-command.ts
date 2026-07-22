// The payload of the payment.send-card-statements Task. It carries through as-is the target
// period the Scheduler computed at enqueue time (see
// payment/infrastructure/previous-statement-month.ts) — for the same reason as
// account.apply-daily-interest's today, so the Consumer never recomputes it from the actual
// clock at processing time.
export class SendCardStatementsCommand {
  public readonly statementMonth: string
  public readonly monthStart: Date
  public readonly monthEnd: Date

  constructor(command: { statementMonth: string; monthStart: Date; monthEnd: Date }) {
    this.statementMonth = command.statementMonth
    this.monthStart = command.monthStart
    this.monthEnd = command.monthEnd
  }
}
