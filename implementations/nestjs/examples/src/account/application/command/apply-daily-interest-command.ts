// account.apply-daily-interest Task의 payload. today는 Scheduler가 enqueue 시점에
// 계산해 그대로 실어 보낸다 — Consumer가 처리를 늦게(예: SQS 적체) 하더라도 "어느 날짜
// 배치인지"가 바뀌지 않도록 하기 위함이다(payload에 계산 결과를 담아 보내는 이유는
// docs/architecture/scheduling.md#payload-크기-제한과 같은 맥락 — 처리 시점의 실제
// 시계가 아니라 적재 시점의 의도를 그대로 전달한다).
export class ApplyDailyInterestCommand {
  public readonly today: Date

  constructor(command: { today: Date }) {
    this.today = command.today
  }
}
