// payment.send-card-statements의 대상 기간("지난 한 달")을 계산하는 순수 함수다.
// Scheduler가 이 함수로 계산한 결과(statementMonth/monthStart/monthEnd)를 Task
// payload에 그대로 실어 보낸다 — Consumer가 이 값을 처리 시점의 실제 시계로 다시
// 계산하면, SQS 적체로 처리가 늦어질 때(달이 바뀐 뒤 처리되는 등) 잘못된 달을 닫아버릴
// 수 있기 때문이다(account.apply-daily-interest의 today payload와 같은 이유).
//
// UTC 달력 기준 "지난 한 달 전체"(이번 달 1일 이전, 지난 달 1일 이후)를 반환한다.
export function computePreviousStatementMonth(now: Date): {
  statementMonth: string
  monthStart: Date
  monthEnd: Date
} {
  const monthStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
  const monthEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1))
  const statementMonth = `${monthStart.getUTCFullYear()}-${String(monthStart.getUTCMonth() + 1).padStart(2, '0')}`
  return { statementMonth, monthStart, monthEnd }
}
