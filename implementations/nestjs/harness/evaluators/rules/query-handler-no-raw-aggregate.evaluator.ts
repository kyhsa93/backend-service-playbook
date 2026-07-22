// The query-handler-no-raw-aggregate evaluator — a Query handler/Controller must always
// return a dedicated Result/DTO type, never returning the Domain Aggregate as-is (guide:
// docs/architecture/api-response.md — the response is wrapped in a Result/ResponseBody DTO,
// never exposing a generic wrapper or the raw Aggregate as-is).
//
// Determining the Aggregate name: instead of hardcoding it, it's dynamically extracted from
// the parameter type of the abstract `save<Noun>(<param>: <Type>)` method in
// `src/<bc>/domain/*-repository.ts` — since the repository-naming rule already enforces the
// `save<Noun>` signature, this generalizes across this entire repo (Account/Card/Payment/Refund/Credential)
// and any newly created domain (via the create-domain.js scaffolding).
//
// Scope: fails if the method return-type annotation
// (`Promise<X>` / `Promise<X[]>`, or the R in `IQueryHandler<Q, R>`) in
// application/query/**/*.ts (the Query interface·QueryHandler) and interface/**/*.ts (the
// Controller) exactly matches the Aggregate type name extracted above. A type wrapped in a
// Result/ResponseBody (`Promise<GetAccountResult>`) has a different name, so it isn't a target.
//
// Applicability: skipped if no Aggregate name can be found at all, or there are no target files.

import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { classifyLayer, readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = '../../docs/architecture/api-response.md#result-object-design'

function isRepositoryDomainFile(filePath: string): boolean {
  const normalized = filePath.replace(/\\/g, '/')
  return classifyLayer(filePath) === 'domain' && /-repository\.ts$/.test(normalized)
}

// Extracts Type from an abstract save<Noun>(param: Type) method inside an abstract class.
function extractAggregateNamesFromRepository(filePath: string): string[] {
  const sf = readSourceFile(filePath)
  const names: string[] = []

  function visit(node: ts.Node) {
    if (ts.isMethodDeclaration(node) && node.name && ts.isIdentifier(node.name) && /^save([A-Z]|$)/.test(node.name.text)) {
      const param = node.parameters[0]
      if (param?.type && ts.isTypeReferenceNode(param.type)) {
        names.push(param.type.typeName.getText(sf))
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return names
}

// Extracts "bare" entity-name candidates from a textual type expression (`Foo`, `Foo[]`,
// `Foo | null`, `Array<Foo>`) — each piece after stripping the union/array/generic wrapper.
function extractBareTypeCandidates(typeText: string): string[] {
  return typeText
    .split('|')
    .map((part) => part.trim())
    .map((part) => part.replace(/\[\]\s*$/, ''))
    .map((part) => {
      const arrayGeneric = part.match(/^Array\s*<\s*(.+)\s*>$/)
      return arrayGeneric ? arrayGeneric[1].trim() : part
    })
    .filter(Boolean)
}

interface Violation {
  ruleId: string
  message: string
}

function inspectFile(filePath: string, aggregateNames: Set<string>): Violation[] {
  const sf = readSourceFile(filePath)
  const violations: Violation[] = []

  function checkReturnType(typeNode: ts.TypeNode | undefined, ownerLabel: string, memberLabel: string) {
    if (!typeNode) return
    let innerText: string | null = null

    if (ts.isTypeReferenceNode(typeNode) && typeNode.typeName.getText(sf) === 'Promise' && typeNode.typeArguments?.[0]) {
      innerText = typeNode.typeArguments[0].getText(sf)
    }

    if (!innerText) return

    for (const candidate of extractBareTypeCandidates(innerText)) {
      if (aggregateNames.has(candidate)) {
        violations.push({
          ruleId: 'query-handler-no-raw-aggregate.raw-aggregate-return',
          message: `${ownerLabel}.${memberLabel} — returns Promise<${innerText}>. Must not return the Domain Aggregate (${candidate}) as-is; wrap it in a dedicated Result/DTO type`
        })
      }
    }
  }

  function visit(node: ts.Node) {
    if (ts.isClassDeclaration(node) && node.name) {
      const className = node.name.text

      // Also checks the second type argument of implements IQueryHandler<Query, Result>.
      for (const clause of node.heritageClauses ?? []) {
        if (clause.token !== ts.SyntaxKind.ImplementsKeyword) continue
        for (const t of clause.types) {
          if (t.expression.getText(sf) !== 'IQueryHandler') continue
          const resultArg = t.typeArguments?.[1]
          if (!resultArg) continue
          for (const candidate of extractBareTypeCandidates(resultArg.getText(sf))) {
            if (aggregateNames.has(candidate)) {
              violations.push({
                ruleId: 'query-handler-no-raw-aggregate.raw-aggregate-return',
                message: `${className} — returns IQueryHandler<..., ${resultArg.getText(sf)}>. Must not return the Domain Aggregate (${candidate}) as-is; wrap it in a dedicated Result/DTO type`
              })
            }
          }
        }
      }

      for (const member of node.members) {
        if (!ts.isMethodDeclaration(member) || !member.name) continue
        const memberName = ts.isIdentifier(member.name) ? member.name.text : member.name.getText(sf)
        checkReturnType(member.type, className, memberName)
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return violations
}

export function evaluateQueryHandlerNoRawAggregate(root: string): EvaluatorResult {
  const srcRoot = path.join(root, 'src')
  const allFiles = walkTsFiles(srcRoot)

  const aggregateNames = new Set<string>()
  for (const file of allFiles.filter(isRepositoryDomainFile)) {
    for (const name of extractAggregateNamesFromRepository(file)) aggregateNames.add(name)
  }

  const targetFiles = allFiles.filter((f) => {
    if (f.endsWith('.spec.ts')) return false
    const layer = classifyLayer(f)
    if (layer === 'interface') return true
    return layer === 'application' && f.replace(/\\/g, '/').includes('/query/')
  })

  if (aggregateNames.size === 0 || targetFiles.length === 0) {
    return { name: 'query-handler-no-raw-aggregate', score: 0, maxScore: 0, failures: [] }
  }

  const failures: EvaluatorFailure[] = []
  let score = 15
  const rel = (f: string) => path.relative(root, f)

  for (const file of targetFiles) {
    for (const v of inspectFile(file, aggregateNames)) {
      failures.push({
        ruleId: v.ruleId,
        severity: 'high',
        message: `${rel(file)} — ${v.message}`,
        docRef: DOC_REF
      })
      score -= penaltyFor('high')
    }
  }

  return {
    name: 'query-handler-no-raw-aggregate',
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  }
}
