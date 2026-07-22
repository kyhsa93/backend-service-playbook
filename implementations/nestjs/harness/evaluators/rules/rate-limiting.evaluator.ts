import * as fs from 'node:fs'
import * as path from 'node:path'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { findClassDecorator } from '../shared/ast-utils'

const DOC = 'docs/architecture/rate-limiting.md'

function walkTsFiles(root: string): string[] {
  const out: string[] = []
  if (!fs.existsSync(root)) return out
  for (const entry of fs.readdirSync(root)) {
    if (['node_modules', 'dist', 'coverage', '.git'].includes(entry)) continue
    const full = path.join(root, entry)
    if (fs.statSync(full).isDirectory()) { out.push(...walkTsFiles(full)); continue }
    if (full.endsWith('.ts') && !full.endsWith('.d.ts')) out.push(full)
  }
  return out
}

function hasThrottlerUsage(files: string[]): boolean {
  return files.some((f) => {
    const c = fs.readFileSync(f, 'utf-8')
    return c.includes('ThrottlerModule') || c.includes('@nestjs/throttler')
  })
}

function hasThrottlerInPackage(root: string): boolean {
  const pkgPath = path.join(root, 'package.json')
  if (!fs.existsSync(pkgPath)) return false
  try {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8')) as {
      dependencies?: Record<string, string>
      devDependencies?: Record<string, string>
    }
    return '@nestjs/throttler' in { ...pkg.dependencies, ...pkg.devDependencies }
  } catch { return false }
}

export function evaluateRateLimiting(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot)

  if (!hasThrottlerInPackage(root) && !hasThrottlerUsage(files)) {
    return { name: 'rate-limiting', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 10
  const allContent = files.map((f) => fs.readFileSync(f, 'utf-8')).join('\n')

  // ThrottlerModule.forRoot or forRootAsync configuration
  if (!/ThrottlerModule\.(forRoot|forRootAsync)\s*\(/.test(allContent)) {
    failures.push({
      ruleId: 'rate-limiting.throttler-module-missing',
      severity: 'high',
      message: 'ThrottlerModule.forRoot() 또는 forRootAsync() 설정이 없습니다. AppModule에 등록해야 합니다.',
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // Confirms APP_GUARD + ThrottlerGuard is actually applied — to filter out dead code that's
  // only defined (installed·imported) but never actually registered, the check is scoped to
  // either the actual @Module decorator body (the providers array) or a controller's
  // @UseGuards(ThrottlerGuard), rather than the whole file concatenated as one string. If
  // neither is present, it's treated as ThrottlerModule being configured but never applied.
  const moduleFiles = files.filter((f) => /@Module\s*\(/.test(fs.readFileSync(f, 'utf-8')))
  const wiredGlobally = moduleFiles.some((f) => {
    const decoratorText = findClassDecorator(f, 'Module')
    return decoratorText !== null && /APP_GUARD/.test(decoratorText) && /ThrottlerGuard/.test(decoratorText)
  })

  const wiredViaUseGuards = files.some((f) => {
    const content = fs.readFileSync(f, 'utf-8')
    return /@Controller\s*\(/.test(content) && /@UseGuards\([^)]*ThrottlerGuard[^)]*\)/.test(content)
  })

  if (!wiredGlobally && !wiredViaUseGuards) {
    failures.push({
      ruleId: 'rate-limiting.app-guard-missing',
      severity: 'medium',
      message: '{ provide: APP_GUARD, useClass: ThrottlerGuard } 전역 가드 등록(@Module providers)이나 컨트롤러의 @UseGuards(ThrottlerGuard) 적용이 없습니다 — ThrottlerModule 설정만 있고 실제로 적용되지 않은(dead code) 상태일 수 있습니다.',
      docRef: DOC
    })
    score -= penaltyFor('medium')
  }

  return {
    name: 'rate-limiting',
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  }
}
