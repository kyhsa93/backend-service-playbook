// Fixture-based regression test runner.
//
// For every fixture under tests/fixtures/<evaluator>/<case>/ we invoke
// the matching evaluator and compare against expected.json.
//
// expected.json schema:
//   {
//     "name": string,               // must match evaluator.name
//     "applicable": boolean,        // true if maxScore > 0
//     "expectedFailureRuleIds": string[]
//   }

import * as fs from 'node:fs'
import * as path from 'node:path'

import { evaluateStructure } from '../evaluators/rules/structure.evaluator'
import { evaluateFileNaming } from '../evaluators/rules/file-naming.evaluator'
import { evaluateClassNaming } from '../evaluators/rules/class-naming.evaluator'
import { evaluateLayerDependency } from '../evaluators/rules/layer-dependency.evaluator'
import { evaluateRepositoryPattern } from '../evaluators/rules/repository-pattern.evaluator'
import { evaluateCqrsPattern } from '../evaluators/rules/cqrs-pattern.evaluator'
import { clearAllWorkspaces } from '../evaluators/shared/workspace'
import type { EvaluatorResult } from '../evaluators/shared/types'

type EvaluatorFn = (root: string) => EvaluatorResult

const EVALUATORS: Record<string, EvaluatorFn> = {
  structure: evaluateStructure,
  'file-naming': evaluateFileNaming,
  'class-naming': evaluateClassNaming,
  'layer-dependency': evaluateLayerDependency,
  'repository-pattern': evaluateRepositoryPattern,
  'cqrs-pattern': evaluateCqrsPattern,
}

interface Expected {
  name: string
  applicable: boolean
  expectedFailureRuleIds: string[]
}

function collectCases(fixturesRoot: string): Array<{ evaluator: string; caseName: string; caseRoot: string }> {
  const cases: Array<{ evaluator: string; caseName: string; caseRoot: string }> = []
  if (!fs.existsSync(fixturesRoot)) return cases
  for (const evaluator of fs.readdirSync(fixturesRoot)) {
    const evalDir = path.join(fixturesRoot, evaluator)
    if (!fs.statSync(evalDir).isDirectory()) continue
    for (const caseName of fs.readdirSync(evalDir)) {
      const caseRoot = path.join(evalDir, caseName)
      if (!fs.statSync(caseRoot).isDirectory()) continue
      cases.push({ evaluator, caseName, caseRoot })
    }
  }
  return cases
}

function multisetEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  const count = new Map<string, number>()
  for (const x of a) count.set(x, (count.get(x) ?? 0) + 1)
  for (const x of b) {
    const n = count.get(x)
    if (!n) return false
    count.set(x, n - 1)
  }
  return true
}

function run(): void {
  const fixturesRoot = path.resolve(__dirname, 'fixtures')
  const cases = collectCases(fixturesRoot)
  let pass = 0
  let fail = 0

  for (const c of cases) {
    clearAllWorkspaces()

    const expectedPath = path.join(c.caseRoot, 'expected.json')
    if (!fs.existsSync(expectedPath)) {
      console.error(`  SKIP ${c.evaluator}/${c.caseName}: expected.json 없음`)
      continue
    }

    const expected: Expected = JSON.parse(fs.readFileSync(expectedPath, 'utf-8'))
    const evaluator = EVALUATORS[c.evaluator]
    if (!evaluator) {
      console.error(`  SKIP ${c.evaluator}/${c.caseName}: evaluator 매핑 없음`)
      continue
    }

    const result = evaluator(c.caseRoot)
    const applicable = result.maxScore > 0
    const actualRuleIds = result.failures.map(f => f.ruleId)

    const nameMatch = result.name === expected.name
    const applicabilityMatch = applicable === expected.applicable
    const failuresMatch = multisetEqual(actualRuleIds, expected.expectedFailureRuleIds)

    if (nameMatch && applicabilityMatch && failuresMatch) {
      console.log(`  PASS ${c.evaluator}/${c.caseName}`)
      pass += 1
    } else {
      console.log(`  FAIL ${c.evaluator}/${c.caseName}`)
      if (!nameMatch) console.log(`    name: expected=${expected.name} actual=${result.name}`)
      if (!applicabilityMatch) console.log(`    applicable: expected=${expected.applicable} actual=${applicable} (maxScore=${result.maxScore})`)
      if (!failuresMatch) {
        const expectedSet = new Map<string, number>()
        for (const x of expected.expectedFailureRuleIds) expectedSet.set(x, (expectedSet.get(x) ?? 0) + 1)
        const actualSet = new Map<string, number>()
        for (const x of actualRuleIds) actualSet.set(x, (actualSet.get(x) ?? 0) + 1)
        const missing = expected.expectedFailureRuleIds.filter((x, i, arr) => {
          const cnt = arr.slice(0, i + 1).filter(y => y === x).length
          return cnt > (actualSet.get(x) ?? 0)
        })
        const extra = actualRuleIds.filter((x, i, arr) => {
          const cnt = arr.slice(0, i + 1).filter(y => y === x).length
          return cnt > (expectedSet.get(x) ?? 0)
        })
        if (missing.length > 0) console.log(`    missing ruleIds: ${[...new Set(missing)].join(', ')}`)
        if (extra.length > 0)   console.log(`    extra ruleIds:   ${[...new Set(extra)].join(', ')}`)
      }
      fail += 1
    }
  }

  console.log(`\n${pass} passed, ${fail} failed, ${cases.length} total`)
  if (fail > 0) process.exit(1)
}

run()
