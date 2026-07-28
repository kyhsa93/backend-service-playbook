export class ForecastSpendingCommand {
  // The month being predicted, YYYY-MM — always the current month, since the job trains on
  // history strictly before it (see ForecastSpendingCommandHandler).
  public readonly forecastMonth: string

  constructor(command: Pick<ForecastSpendingCommand, 'forecastMonth'>) {
    Object.assign(this, command)
  }
}
