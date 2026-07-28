import { AnomalyDetectionService } from '@/account/domain/anomaly-detection-service'

describe('AnomalyDetectionService', () => {
  const service = new AnomalyDetectionService()

  it('isAnomalous_when_history_has_fewer_than_5_withdrawals_then_returns_false_regardless_of_amount', () => {
    const result = service.isAnomalous([10000, 10000, 10000, 10000], 5000000)

    expect(result).toBe(false)
  })

  it('isAnomalous_when_the_amount_is_close_to_the_historical_mean_then_returns_false', () => {
    const history = [10000, 12000, 9000, 11000, 10500, 9500]

    const result = service.isAnomalous(history, 10800)

    expect(result).toBe(false)
  })

  it('isAnomalous_when_the_amount_is_far_beyond_the_historical_spread_then_returns_true', () => {
    const history = [10000, 12000, 9000, 11000, 10500, 9500]

    const result = service.isAnomalous(history, 5000000)

    expect(result).toBe(true)
  })

  it('isAnomalous_when_history_is_perfectly_uniform_and_the_amount_matches_it_then_returns_false', () => {
    const result = service.isAnomalous([10000, 10000, 10000, 10000, 10000], 10000)

    expect(result).toBe(false)
  })

  it('isAnomalous_when_history_is_perfectly_uniform_and_the_amount_differs_then_returns_true', () => {
    const result = service.isAnomalous([10000, 10000, 10000, 10000, 10000], 10001)

    expect(result).toBe(true)
  })
})
