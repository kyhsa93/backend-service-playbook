// The user-context-store evaluator — verifies that Controllers read the authenticated user via
// UserContextStore rather than directly off the request object (guide: docs/architecture/
// authentication.md, docs/architecture/cross-cutting-concerns.md).
//
// Applicability: runs if a *-controller.ts file exists (maxScore = 10).
//
// Rule: a *-controller.ts file must not read `req.user`/`request.user` (or destructure `.user`
// off an @Req()-typed request parameter) — that couples the Handler to the raw HTTP request
// object instead of a plain request-scoped value, and is exactly the anti-pattern this repo
// used to have before introducing UserContextStore. `request.__verifiedUser` inside AuthGuard
// itself is the one sanctioned exception (the internal Guard→Interceptor handoff — see
// user-context.interceptor.ts) and is deliberately not matched by this rule's pattern.

import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'

const DOC_REF = 'docs/architecture/authentication.md'
// Matches req.user / request.user, but not req.__verifiedUser (the sanctioned internal handoff).
const DIRECT_USER_ACCESS = /\b(req|request)\.user\b/g

function walkFiles(root: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out

  for (const entry of fs.readdirSync(root)) {
    if (entry === 'node_modules' || entry === 'dist' || entry === 'coverage' || entry === '.git') continue
    const fullPath = path.join(root, entry)
    const stat = fs.statSync(fullPath)
    if (stat.isDirectory()) {
      out.push(...walkFiles(fullPath))
      continue
    }
    if (fullPath.endsWith('controller.ts') && !fullPath.endsWith('.spec.ts')) out.push(fullPath)
  }

  return out
}

function lineOf(source: string, index: number): number {
  return source.slice(0, index).split('\n').length
}

export function evaluateUserContextStore(root: string): EvaluatorResult {
  const controllerFiles = walkFiles(path.join(root, 'src'))

  if (controllerFiles.length === 0) {
    return { name: 'user-context-store', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 10
  const rel = (file: string) => path.relative(root, file)

  for (const file of controllerFiles) {
    const content = fs.readFileSync(file, 'utf-8')
    DIRECT_USER_ACCESS.lastIndex = 0
    let match: RegExpExecArray | null
    while ((match = DIRECT_USER_ACCESS.exec(content)) !== null) {
      failures.push({
        ruleId: 'user-context-store.direct-request-access-forbidden',
        severity: 'medium',
        message: `${rel(file)}:${lineOf(content, match.index)} reads the authenticated user directly off the request object (${match[0]}) — use UserContextStore.getRequesterId()/getUser() instead`,
        docRef: DOC_REF
      })
      score -= 3
    }
  }

  return {
    name: 'user-context-store',
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  }
}
