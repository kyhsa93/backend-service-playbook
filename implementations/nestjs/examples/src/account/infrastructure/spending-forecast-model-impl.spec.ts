import { SpendingForecastModelImpl } from '@/account/infrastructure/spending-forecast-model-impl'

describe('SpendingForecastModelImpl', () => {
  const model = new SpendingForecastModelImpl()

  it('predict_when_history_is_a_perfect_linear_trend_then_extrapolates_exactly_with_high_confidence', () => {
    const prediction = model.predict([
      { analysisMonth: '2026-04', totalAmount: 10000 },
      { analysisMonth: '2026-05', totalAmount: 20000 },
      { analysisMonth: '2026-06', totalAmount: 30000 }
    ])

    expect(prediction.predictedAmount).toBe(40000)
    expect(prediction.confidence).toBe('HIGH')
  })

  it('predict_when_history_is_perfectly_flat_then_predicts_the_same_amount_with_high_confidence', () => {
    const prediction = model.predict([
      { analysisMonth: '2026-04', totalAmount: 15000 },
      { analysisMonth: '2026-05', totalAmount: 15000 },
      { analysisMonth: '2026-06', totalAmount: 15000 }
    ])

    expect(prediction.predictedAmount).toBe(15000)
    expect(prediction.confidence).toBe('HIGH')
  })

  it('predict_when_history_is_noisy_and_non-linear_then_reports_lower_confidence', () => {
    const prediction = model.predict([
      { analysisMonth: '2026-01', totalAmount: 5000 },
      { analysisMonth: '2026-02', totalAmount: 40000 },
      { analysisMonth: '2026-03', totalAmount: 3000 },
      { analysisMonth: '2026-04', totalAmount: 35000 },
      { analysisMonth: '2026-05', totalAmount: 4000 },
      { analysisMonth: '2026-06', totalAmount: 38000 }
    ])

    expect(prediction.confidence).not.toBe('HIGH')
  })

  it('predict_when_the_trend_is_sharply_decreasing_then_floors_the_prediction_at_0_instead_of_going_negative', () => {
    const prediction = model.predict([
      { analysisMonth: '2026-04', totalAmount: 30000 },
      { analysisMonth: '2026-05', totalAmount: 15000 },
      { analysisMonth: '2026-06', totalAmount: 1000 }
    ])

    expect(prediction.predictedAmount).toBe(0)
  })
})
