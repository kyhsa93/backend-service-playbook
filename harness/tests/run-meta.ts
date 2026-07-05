// Meta-validation for the harness itself.
//
// Checks:
// 1. Every evaluator has a fixture directory with at least one good/ and one bad-* case.
// 2. Every expected.json exists in each case directory.
// 3. Every docRef in evaluator source files points to an existing file.

import * as fs from 'node:fs'
import * as path from 'node:path'

const HARNESS_ROOT = path.resolve(__dirname, '..')
const REPO_ROOT = path.resolve(HARNESS_ROOT, '..')
const RULES_ROOT = path.join(HARNESS_ROOT, 'evaluators', 'rules')
const FIXTURES_ROOT = path.join(HARNESS_ROOT, 'tests', 'fixtures')

function listEvaluatorNames(): string[] {
  return fs.readdirSync(RULES_ROOT)
    .filter(file => file.endsWith('.evaluator.ts'))
    .map(file => file.replace(/\.evaluator\.ts$/, ''))
    .sort()
}

function walkFiles(root: string, predicate: (file: string) => boolean): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out
  for (const entry of fs.readdirSync(root)) {
    const fullPath = path.join(root, entry)
    const stat = fs.statSync(fullPath)
    if (stat.isDirectory()) {
      out.push(...walkFiles(fullPath, predicate))
    } else if (predicate(fullPath)) {
      out.push(fullPath)
    }
  }
  return out
}

function validateFixtureCoverage(): string[] {
  const failures: string[] = []
  for (const name of listEvaluatorNames()) {
    const fixtureDir = path.join(FIXTURES_ROOT, name)
    if (!fs.existsSync(fixtureDir)) {
      failures.push(`fixture directory missing: tests/fixtures/${name}`)
      continue
    }

    const cases = fs.readdirSync(fixtureDir)
      .filter(c => fs.statSync(path.join(fixtureDir, c)).isDirectory())

    if (!cases.includes('good')) {
      failures.push(`good fixture missing: tests/fixtures/${name}/good`)
    }
    if (!cases.some(c => c.startsWith('bad'))) {
      failures.push(`bad fixture missing: tests/fixtures/${name}/bad-*`)
    }

    for (const caseName of cases) {
      const expectedPath = path.join(fixtureDir, caseName, 'expected.json')
      if (!fs.existsSync(expectedPath)) {
        failures.push(`expected.json missing: tests/fixtures/${name}/${caseName}`)
      }
    }
  }
  return failures
}

function validateDocRefs(): string[] {
  const failures: string[] = []
  for (const file of walkFiles(RULES_ROOT, f => f.endsWith('.ts'))) {
    const source = fs.readFileSync(file, 'utf-8')
    const regex = /docRef\s*:\s*['"]([^'"]+)['"]/g
    let match: RegExpExecArray | null
    while ((match = regex.exec(source)) !== null) {
      const [docPath] = match[1].split('#')
      const abs = path.join(REPO_ROOT, docPath)
      if (!fs.existsSync(abs)) {
        failures.push(`${path.relative(REPO_ROOT, file)} has invalid docRef: ${match[1]}`)
      }
    }
  }
  return failures
}

function run(): void {
  const failures = [...validateFixtureCoverage(), ...validateDocRefs()]

  if (failures.length === 0) {
    console.log('  PASS harness meta validation')
    return
  }

  console.error('  FAIL harness meta validation')
  for (const f of failures) {
    console.error(`    - ${f}`)
  }
  process.exit(1)
}

run()
