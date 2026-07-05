export type Severity = 'critical' | 'high' | 'medium' | 'low'

export interface EvaluatorFailure {
  ruleId: string
  severity: Severity
  message: string
  /** Relative path to the guide doc section that explains this rule. */
  docRef?: string
}

export interface EvaluatorResult {
  name: string
  score: number
  maxScore: number
  failures: EvaluatorFailure[]
}
