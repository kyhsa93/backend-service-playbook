// The bootstrap-healthcheck evaluator — verifies the required operational configuration of the NestJS bootstrap and health endpoint
// (guide: docs/architecture/bootstrap.md, docs/architecture/graceful-shutdown.md).

import * as fs from 'node:fs'
import * as path from 'node:path'
import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

const BOOTSTRAP_DOC_REF = 'docs/architecture/bootstrap.md'
const HEALTH_DOC_REF = 'docs/architecture/graceful-shutdown.md'

// The app-object setup (ValidationPipe, enableShutdownHooks, etc.) may live either directly in
// main.ts or in a shared setup module that main.ts imports (e.g. src/app-setup.ts) so E2E tests
// can apply the identical configuration to the real AppModule. Both locations are accepted, but
// a setup file only counts when main.ts actually imports it — an orphaned setup file that the
// bootstrap never calls must not satisfy the rules.
function readBootstrapSource(root: string, main: string): string {
  const setupImport = /from\s+'(?:@\/|\.{1,2}\/)([\w./-]*app-setup)'/.exec(main)
  if (!setupImport) return main

  const setupPath = path.join(root, 'src', `${setupImport[1]}.ts`)
  if (!fs.existsSync(setupPath)) return main

  return main + '\n' + fs.readFileSync(setupPath, 'utf-8')
}

export function evaluateBootstrapHealthcheck(root: string): EvaluatorResult {
  const mainPath = path.join(root, 'src', 'main.ts')
  if (!fs.existsSync(mainPath)) return { name: 'bootstrap-healthcheck', score: 0, maxScore: 0, failures: [] }

  const failures: EvaluatorFailure[] = []
  let score = 20
  const main = readBootstrapSource(root, fs.readFileSync(mainPath, 'utf-8'))

  if (!main.includes('enableShutdownHooks')) {
    failures.push({ ruleId: 'bootstrap.shutdown-hooks', severity: 'high', message: 'enableShutdownHooks is missing', docRef: HEALTH_DOC_REF })
    score -= 4
  }

  if (!main.includes('ValidationPipe')) {
    failures.push({ ruleId: 'bootstrap.validation-pipe', severity: 'high', message: 'ValidationPipe is missing', docRef: BOOTSTRAP_DOC_REF })
    score -= 4
  }

  return { name: 'bootstrap-healthcheck', score: Math.max(score, 0), maxScore: 20, failures }
}
