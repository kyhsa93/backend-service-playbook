// The payload of the account.apply-daily-interest Task. `today` is computed by the Scheduler
// at enqueue time and carried through as-is — this is so that "which day's batch this is"
// doesn't change even if the Consumer processes it late (e.g. SQS backlog). (The reason the
// payload carries the computed result is the same idea as
// docs/architecture/scheduling.md#payload-size-limit — it conveys the intent as of the
// enqueue moment, not the actual clock at processing time.)
export class ApplyDailyInterestCommand {
  public readonly today: Date

  constructor(command: { today: Date }) {
    this.today = command.today
  }
}
