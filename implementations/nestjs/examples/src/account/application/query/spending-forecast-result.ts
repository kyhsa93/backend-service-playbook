import { ApiProperty } from '@nestjs/swagger'

export class SpendingForecastResult {
  @ApiProperty({ description: 'The forecasted month, in YYYY-MM form.' })
  public readonly forecastMonth: string

  @ApiProperty({ description: "The model's predicted total withdrawal amount for the month." })
  public readonly predictedAmount: number

  @ApiProperty({ description: "The model's confidence in the prediction, based on how well a linear trend fits the account's history.", enum: ['LOW', 'MEDIUM', 'HIGH'] })
  public readonly confidence: string

  @ApiProperty({ description: 'How many months of history the model was trained on for this prediction.' })
  public readonly historyMonthsUsed: number

  @ApiProperty({ description: 'When this forecast was computed.' })
  public readonly createdAt: Date
}
