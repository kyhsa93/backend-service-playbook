import { EvaluatorResult, EvaluatorFailure } from './types'

export interface AggregateReport {
  total: number
  rawScore: number
  rawMax: number
  breakdown: {
    structure: number
    naming: number
    architecture: number
  }
  breakdownMax: {
    structure: number
    naming: number
    architecture: number
  }
  failures: EvaluatorFailure[]
  skippedEvaluators: string[]
}

export function aggregate(results: EvaluatorResult[]): AggregateReport {
  const breakdown = { structure: 0, naming: 0, architecture: 0 }
  const breakdownMax = { structure: 0, naming: 0, architecture: 0 }

  let rawScore = 0
  let rawMax = 0
  const failures: EvaluatorFailure[] = []
  const skippedEvaluators: string[] = []

  for (const r of results) {
    failures.push(...r.failures)

    if (r.maxScore <= 0) {
      skippedEvaluators.push(r.name)
      continue
    }

    rawScore += r.score
    rawMax += r.maxScore

    const bucket: keyof typeof breakdown = (() => {
      if (r.name === 'structure') return 'structure'
      if (r.name === 'file-naming' || r.name === 'class-naming') return 'naming'
      return 'architecture'
    })()

    breakdown[bucket] += r.score
    breakdownMax[bucket] += r.maxScore
  }

  const total = rawMax > 0 ? Math.round((rawScore / rawMax) * 100) : 0
  return { total, rawScore, rawMax, breakdown, breakdownMax, failures, skippedEvaluators }
}
