import * as assert from 'node:assert/strict'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

import { notApplicableFor } from '../evaluators/shared/profile'

function project(files: string[]): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'harness-profile-'))
  for (const file of files) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
    fs.writeFileSync(path.join(root, file), '')
  }
  return root
}

const evaluators = (profile: 'benchmark' | 'adopt', root: string): string[] =>
  notApplicableFor(profile, root).map((entry) => entry.evaluator).sort()

const cases: Array<[string, () => void]> = [
  ['benchmark applies every evaluator', () => {
    assert.deepEqual(evaluators('benchmark', project(['src/app.module.ts'])), [])
  }],
  ['adopt on a plain Nest project drops layout, house naming and build', () => {
    assert.deepEqual(
      evaluators('adopt', project(['src/users/users.module.ts'])),
      ['build', 'cqrs-pattern', 'file-naming', 'structure']
    )
  }],
  ['adopt on a playbook-layout project with deps keeps layout and build', () => {
    assert.deepEqual(
      evaluators('adopt', project(['src/account/domain/account.ts', 'node_modules/.bin/tsc'])),
      ['file-naming']
    )
  }]
]

let failed = 0
for (const [name, run] of cases) {
  try {
    run()
    console.log(`  PASS ${name}`)
  } catch (error) {
    failed += 1
    console.log(`  FAIL ${name}\n${String(error)}`)
  }
}
if (failed > 0) process.exit(1)
