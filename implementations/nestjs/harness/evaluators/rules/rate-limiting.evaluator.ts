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

  // ThrottlerModule.forRoot 또는 forRootAsync 설정
  if (!/ThrottlerModule\.(forRoot|forRootAsync)\s*\(/.test(allContent)) {
    failures.push({
      ruleId: 'rate-limiting.throttler-module-missing',
      severity: 'high',
      message: 'ThrottlerModule.forRoot() 또는 forRootAsync() 설정이 없습니다. AppModule에 등록해야 합니다.',
      docRef: DOC
    })
    score -= penaltyFor('high')
  }

  // APP_GUARD + ThrottlerGuard가 실제로 적용되어 있는지 확인 — 정의(설치·import)만 있고 실제
  // 등록이 안 된 dead code를 걸러내기 위해, 파일 전체를 이어붙인 문자열이 아니라 실제 @Module
  // 데코레이터 본문(providers 배열) 또는 컨트롤러의 @UseGuards(ThrottlerGuard) 중 하나에 한정해
  // 검사한다. 둘 중 하나도 없으면 ThrottlerModule 설정만 있고 미적용 상태로 본다.
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
