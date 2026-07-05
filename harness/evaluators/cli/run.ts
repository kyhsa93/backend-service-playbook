import * as path from 'node:path'

import { evaluateStructure } from '../rules/structure.evaluator'
import { evaluateFileNaming } from '../rules/file-naming.evaluator'
import { evaluateClassNaming } from '../rules/class-naming.evaluator'
import { evaluateLayerDependency } from '../rules/layer-dependency.evaluator'
import { evaluateRepositoryPattern } from '../rules/repository-pattern.evaluator'
import { evaluateCqrsPattern } from '../rules/cqrs-pattern.evaluator'
import { aggregate } from '../shared/score'
import type { EvaluatorResult } from '../shared/types'

const EVALUATORS: Record<string, (root: string) => EvaluatorResult> = {
  structure: evaluateStructure,
  'file-naming': evaluateFileNaming,
  'class-naming': evaluateClassNaming,
  'layer-dependency': evaluateLayerDependency,
  'repository-pattern': evaluateRepositoryPattern,
  'cqrs-pattern': evaluateCqrsPattern,
}

function parseArgs(): { projectRoot: string; only: string[] } {
  const args = process.argv.slice(2)
  const onlyIdx = args.indexOf('--only')
  const only = onlyIdx >= 0 ? args[onlyIdx + 1].split(',') : []
  const projectRoot = args.find(a => !a.startsWith('--')) ?? '.'
  return { projectRoot: path.resolve(projectRoot), only }
}

function pad(s: string, width: number): string {
  return s.padEnd(width)
}

function main(): void {
  const { projectRoot, only } = parseArgs()
  const activeEvaluators = only.length > 0
    ? Object.fromEntries(Object.entries(EVALUATORS).filter(([k]) => only.includes(k)))
    : EVALUATORS

  console.log('=== Backend Service Playbook Harness ===')
  console.log(`Project: ${projectRoot}\n`)

  const results: EvaluatorResult[] = []

  for (const [name, fn] of Object.entries(activeEvaluators)) {
    const result = fn(projectRoot)
    results.push(result)

    const skipped = result.maxScore === 0
    const label = skipped ? 'SKIP' : result.score === result.maxScore ? 'PASS' : 'FAIL'
    const scoreStr = skipped ? '(not applicable)' : `${result.score}/${result.maxScore}`
    console.log(`[${pad(name, 20)}] ${label}  ${scoreStr}`)

    for (const f of result.failures) {
      const icon = f.severity === 'critical' || f.severity === 'high' ? '  ✗' : '  ⚠'
      console.log(`${icon} [${f.ruleId}] ${f.message}`)
      if (f.docRef) console.log(`      → ${f.docRef}`)
    }
  }

  const report = aggregate(results)

  console.log('\n--- Score Breakdown ---')
  console.log(`  Structure    : ${report.breakdown.structure}/${report.breakdownMax.structure}`)
  console.log(`  Naming       : ${report.breakdown.naming}/${report.breakdownMax.naming}`)
  console.log(`  Architecture : ${report.breakdown.architecture}/${report.breakdownMax.architecture}`)
  console.log('')

  const grade = report.total >= 90 ? 'A' : report.total >= 75 ? 'B' : report.total >= 60 ? 'C' : 'F'
  const verdict = grade === 'F' ? 'FAIL' : 'PASS'
  console.log(`Total: ${report.rawScore}/${report.rawMax} (${report.total}%)  Grade: ${grade}  ${verdict}`)

  if (report.skippedEvaluators.length > 0) {
    console.log(`Skipped (not applicable): ${report.skippedEvaluators.join(', ')}`)
  }

  if (grade === 'F') process.exit(1)
}

main()
