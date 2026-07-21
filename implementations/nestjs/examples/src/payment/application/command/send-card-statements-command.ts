// payment.send-card-statements Task의 payload. Scheduler가 enqueue 시점에 계산한
// 대상 기간을 그대로 실어 보낸다(payment/infrastructure/previous-statement-month.ts 참고) —
// account.apply-daily-interest의 today와 같은 이유로, Consumer가 처리 시점의 실제
// 시계로 다시 계산하지 않는다.
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
