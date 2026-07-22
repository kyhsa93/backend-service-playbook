import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { walkTsFiles, readSourceFile } from '../shared/ast-utils'

const DOC = 'docs/architecture/repository-pattern.md'
const DOC_REF = `${DOC}#repository-method-naming-rules`

// Targets only the src/<domain>/domain/*-repository.ts pattern — infrastructure's TypeORM
// implementation (*-repository-impl.ts) is excluded since it may have internal query-builder helpers.
function isRepositoryDomainFile(root: string, file: string): boolean {
  const rel = path.relative(root, file).replace(/\\/g, '/')
  return /^src\/[^/]+\/domain\/[^/]+-repository\.ts$/.test(rel)
}

interface AbstractMethod {
  name: string
}

// Collects only the abstract methods inside an abstract class *Repository.
// A concrete method (if any) isn't a target of this rule.
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

// A blocklist approach — narrowly catches only known anti-patterns. A broad positive-match
// grammar (an allowlist) that would also catch a normal method like `hasTransactionWithReference` isn't used.
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
  },
  {
    ruleId: 'repository-naming.update-method',
    test: (name) => /^update([A-Z]|$)/.test(name),
    describe: (name) => `${name}: 별도 update 메서드 금지. 조회 후 Aggregate의 도메인 메서드로 상태를 변경하고 save<Noun>으로 저장한다`
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
