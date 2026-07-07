// build evaluator — runs `tsc --noEmit` in the submission root to verify
// the submitted code actually compiles under strict TypeScript.
//
// Applicability: skipped when submission root has no tsconfig.json (e.g. fixture
// dirs that aren't full NestJS projects).
//
// Failure modes:
//  - tsc exits non-zero: first N error lines captured as failure messages.
//  - tsc binary missing: produces a single critical failure and floors the
//    score; the human reviewer needs to address environment setup.

import { spawnSync } from 'node:child_process'
import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

const MAX_ERROR_LINES = 25
const FALLBACK_TYPESCRIPT_VERSION = '5.7.2'

// package.json의 typescript 버전 지정(^5.7.2, ~5.7.2, 5.7.2 등)에서 npx가 그대로
// 받아들일 수 있는 semver 스펙을 뽑아낸다. 파싱에 실패하면(package.json 없음,
// typescript 미지정 등) 이 저장소가 실제로 사용 중인 버전으로 대체한다 — "latest"보다
// "이 프로젝트가 최근까지 쓰던 버전"이 훨씬 안전한 기본값이다.
function resolvePinnedTypescriptVersion(root: string): string {
  try {
    const pkgPath = path.join(root, 'package.json')
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'))
    const spec: string | undefined = pkg?.devDependencies?.typescript ?? pkg?.dependencies?.typescript
    if (!spec) return FALLBACK_TYPESCRIPT_VERSION
    const version = spec.replace(/^[~^]/, '').trim()
    return /^\d+\.\d+\.\d+$/.test(version) ? version : FALLBACK_TYPESCRIPT_VERSION
  } catch {
    return FALLBACK_TYPESCRIPT_VERSION
  }
}

export function evaluateBuild(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  const tsconfigPath = path.join(root, 'tsconfig.json')
  if (!fs.existsSync(tsconfigPath)) {
    return { name: 'build', score: 0, maxScore: 0, failures: [] }
  }

  // Prefer locally-installed tsc if node_modules exists, else fall back to npx —
  // but pin the npx fallback to the project's own package.json version. An
  // unpinned `-p typescript` installs whatever is latest at run time, which can
  // be a major version ahead of what the project targets (e.g. TS 6.0 vs a
  // project pinned to ^5.7.2) and hard-errors on tsconfig options that are
  // perfectly valid under the pinned version — manufacturing a spurious
  // compile failure that has nothing to do with the submission's own code.
  const localTsc = path.join(root, 'node_modules', '.bin', 'tsc')
  const cmd = fs.existsSync(localTsc) ? localTsc : 'npx'
  const tscPackageSpec = cmd === 'npx' ? `typescript@${resolvePinnedTypescriptVersion(root)}` : 'typescript'
  const args = cmd === 'npx' ? ['--yes', '-p', tscPackageSpec, 'tsc', '--noEmit'] : ['--noEmit']

  const res = spawnSync(cmd, args, {
    cwd: root,
    encoding: 'utf-8',
    env: { ...process.env, CI: '1' }
  })

  if (res.status === 0) {
    return { name: 'build', score: 25, maxScore: 25, failures: [] }
  }

  // Capture error output
  const output = `${res.stdout ?? ''}${res.stderr ?? ''}`.trim()
  const lines = output.split('\n').slice(0, MAX_ERROR_LINES)
  for (const line of lines) {
    if (!line.trim()) continue
    failures.push({
      ruleId: 'build.tsc.error',
      severity: 'critical',
      message: line
    })
  }
  // TypeScript errors are critical — score 0 for any compile failure.
  return { name: 'build', score: 0, maxScore: 25, failures }
}
