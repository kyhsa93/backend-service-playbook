import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { walkTsFiles, readSourceFile } from '../shared/ast-utils'

const DOC = 'docs/architecture/repository-pattern.md'
const DOC_REF = `${DOC}#repository-메서드-네이밍-규칙`

// src/<domain>/domain/*-repository.ts 패턴만 대상으로 한다 — infrastructure의
// TypeORM 구현체(*-repository-impl.ts)는 내부 query-builder 헬퍼가 있을 수 있어 제외.
function isRepositoryDomainFile(root: string, file: string): boolean {
  const rel = path.relative(root, file).replace(/\\/g, '/')
  return /^src\/[^/]+\/domain\/[^/]+-repository\.ts$/.test(rel)
}

interface AbstractMethod {
  name: string
}

// abstract class *Repository 안의 abstract 메서드만 수집한다.
// concrete 메서드(있다면)는 이 규칙의 대상이 아니다.
function extractAbstractRepositoryMethods(filePath: string): AbstractMethod[] {
  const sf = readSourceFile(filePath)
  const methods: AbstractMethod[] = []

  function visit(node: ts.Node) {
    if (ts.isClassDeclaration(node) && node.name) {
      const classMods = (node.modifiers as ts.NodeArray<ts.ModifierLike> | undefined) ?? []
      const isAbstractClass = classMods.some((m) => m.kind === ts.SyntaxKind.AbstractKeyword)
      const isRepositoryClass = /Repository$/.test(node.name.text)

      if (isAbstractClass && isRepositoryClass) {
        for (const member of node.members) {
          if (!ts.isMethodDeclaration(member) || !member.name || !ts.isIdentifier(member.name)) continue
          const memberMods = (member.modifiers as ts.NodeArray<ts.ModifierLike> | undefined) ?? []
          const isAbstractMethod = memberMods.some((m) => m.kind === ts.SyntaxKind.AbstractKeyword)
          if (isAbstractMethod) methods.push({ name: member.name.text })
        }
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return methods
}

interface AntiPattern {
  ruleId: string
  test: (name: string) => boolean
  describe: (name: string) => string
}

// 블록리스트 방식 — 알려진 안티패턴만 좁게 잡는다. `hasTransactionWithReference`
// 같은 정상 메서드까지 잡는 광범위한 긍정 문법(허용 목록)은 쓰지 않는다.
const ANTI_PATTERNS: AntiPattern[] = [
  {
    ruleId: 'repository-naming.find-by-shape',
    test: (name) => /^findBy([A-Z]|$)/.test(name),
    describe: (name) => `${name}: find...By... 형태 금지. 조건은 find<Noun>s(query) 하나의 query 객체 파라미터로 받는다 (예: findAccounts({ accountId, ownerId }))`
  },
  {
    ruleId: 'repository-naming.find-all-bare',
    test: (name) => name === 'findAll',
    describe: () => `findAll: bare findAll 금지. 도메인 명사를 포함한 find<Noun>s로 명명한다 (예: findAccounts)`
  },
  {
    ruleId: 'repository-naming.count-method',
    test: (name) => /^count/.test(name),
    describe: (name) => `${name}: 별도 count 메서드 금지. find<Noun>s가 { items, count } 형태로 개수를 함께 반환한다`
  },
  {
    ruleId: 'repository-naming.save-bare',
    test: (name) => name === 'save',
    describe: () => `save: bare save 금지. 도메인 명사를 포함한 save<Noun>으로 명명한다 (예: saveAccount)`
  },
  {
    ruleId: 'repository-naming.delete-bare',
    test: (name) => name === 'delete',
    describe: () => `delete: bare delete 금지. 도메인 명사를 포함한 delete<Noun>으로 명명한다 (예: deleteAccount)`
  }
]

export function evaluateRepositoryNaming(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const files = walkTsFiles(srcRoot).filter((f) => isRepositoryDomainFile(root, f))

  if (files.length === 0) {
    return { name: 'repository-naming', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of files) {
    const methods = extractAbstractRepositoryMethods(file)

    for (const method of methods) {
      const antiPattern = ANTI_PATTERNS.find((p) => p.test(method.name))
      if (!antiPattern) continue

      failures.push({
        ruleId: antiPattern.ruleId,
        severity: 'high',
        message: `${rel(file)} — ${antiPattern.describe(method.name)}`,
        docRef: DOC_REF
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'repository-naming',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
