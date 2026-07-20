// query-handler-no-raw-aggregate evaluator — Query handler/Controller는 반드시
// 전용 Result/DTO 타입을 반환해야 하며, Domain Aggregate를 그대로 반환하지
// 않는다 (guide: docs/architecture/api-response.md — 응답은 Result/ResponseBody
// DTO로 감싸며, 범용 래퍼든 Aggregate 그대로든 그대로 노출하지 않는다).
//
// Aggregate 이름 판별: 하드코딩 대신 `src/<bc>/domain/*-repository.ts`의
// abstract `save<Noun>(<param>: <Type>)` 메서드 파라미터 타입에서 동적으로
// 추출한다 — repository-naming 규칙이 이미 `save<Noun>` 시그니처를 강제하므로
// 이 저장소 전체(Account/Card/Payment/Refund/Credential)와 새로 생성되는
// 도메인(create-domain.js 스캐폴딩)에도 일반화된다.
//
// Scope: application/query/**/*.ts (Query 인터페이스·QueryHandler)와
// interface/**/*.ts (Controller)의 메서드 반환 타입 애노테이션
// (`Promise<X>` / `Promise<X[]>`, `IQueryHandler<Q, R>`의 R)이 위에서 추출한
// Aggregate 타입명과 정확히 일치하면 실패. Result/ResponseBody로 감싼 타입
// (`Promise<GetAccountResult>`)은 이름이 다르므로 대상이 아니다.
//
// Applicability: Aggregate 이름을 하나도 못 찾거나 대상 파일이 없으면 skip.

import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { classifyLayer, readSourceFile, walkTsFiles } from '../shared/ast-utils'

const DOC_REF = '../../docs/architecture/api-response.md#result-객체-설계'

function isRepositoryDomainFile(filePath: string): boolean {
  const normalized = filePath.replace(/\\/g, '/')
  return classifyLayer(filePath) === 'domain' && /-repository\.ts$/.test(normalized)
}

// abstract class 안의 abstract save<Noun>(param: Type) 메서드에서 Type을 추출한다.
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

// 텍스트 형태의 타입 표현(`Foo`, `Foo[]`, `Foo | null`, `Array<Foo>`)에서
// "bare" 개체명 후보들을 뽑는다 — 유니온/배열/제네릭 래퍼를 벗겨낸 각 조각.
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
          message: `${ownerLabel}.${memberLabel} — Promise<${innerText}> 반환. Domain Aggregate(${candidate})를 그대로 반환하지 말고 전용 Result/DTO 타입으로 감싸야 한다`
        })
      }
    }
  }

  function visit(node: ts.Node) {
    if (ts.isClassDeclaration(node) && node.name) {
      const className = node.name.text

      // implements IQueryHandler<Query, Result>의 두 번째 타입 인자도 검사한다.
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
                message: `${className} — IQueryHandler<..., ${resultArg.getText(sf)}> 반환. Domain Aggregate(${candidate})를 그대로 반환하지 말고 전용 Result/DTO 타입으로 감싸야 한다`
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
