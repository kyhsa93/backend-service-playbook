// error-handling evaluator — Domain/Application 레이어 규칙 + 에러 코드 규칙.
//
// Rules:
// - Domain 레이어 파일에 HttpException 참조 금지.
// - Domain/Application 레이어 파일에 throw new Error() 금지 (ErrorMessage enum 참조 강제 —
//   AGENTS.md "에러는 enum으로 타입화 — free-form 문자열 금지").
// - <domain>-error-message.ts가 있으면 동일 디렉토리에 <domain>-error-code.ts도 존재.
// - <Domain>ErrorMessage 와 <Domain>ErrorCode enum의 항목 수는 1:1로 일치.
// - <Domain>ErrorCode enum 키는 SCREAMING_SNAKE_CASE.
// - generateErrorResponse 매핑 배열의 각 튜플은 [메시지, ExceptionClass, ErrorCode] 3-튜플.
// - 전역 예외 필터(@Catch() + ExceptionFilter)가 구성하는 에러 응답 객체는 정확히
//   { statusCode, code, message, error } 4개 필드여야 함.

import * as fs from 'node:fs'
import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { readSourceFile, walkTsFiles } from '../shared/ast-utils'
import { penaltyFor } from '../shared/penalty'

const DOC_REF_BASE = 'docs/architecture/error-handling.md'
const DOC_REF_ERROR_CODE = `${DOC_REF_BASE}#에러-코드--enum으로-정의-메시지와-11-매핑`
const DOC_REF_CATCH = `${DOC_REF_BASE}#controller--catch-and-rethrow`
const DOC_REF_RESPONSE_SCHEMA = `${DOC_REF_BASE}#에러-응답-형식--표준-json-구조`

// 에러 응답 형식(error-handling.md)이 요구하는 정확히 4개 필드.
const RESPONSE_SCHEMA_FIELDS = ['statusCode', 'code', 'message', 'error']

function kebabToPascal(kebab: string): string {
  return kebab
    .split('-')
    .filter(Boolean)
    .map((s) => s[0].toUpperCase() + s.slice(1))
    .join('')
}

// `throw new Error(...)` 중 인자가 <Domain>ErrorMessage enum 참조(PropertyAccess/ElementAccess)가
// 아닌 경우(raw 문자열 리터럴, 템플릿 리터럴, 인자 없음 등)만 위반으로 잡는다.
// `throw new Error(ErrorMessage['...'])`는 가이드가 요구하는 정상 패턴이므로 매치되지 않는다.
function findGenericErrorThrows(filePath: string): number[] {
  const sf = readSourceFile(filePath)
  const lines: number[] = []
  function visit(node: ts.Node) {
    if (
      ts.isThrowStatement(node)
      && node.expression
      && ts.isNewExpression(node.expression)
      && ts.isIdentifier(node.expression.expression)
      && node.expression.expression.text === 'Error'
    ) {
      const arg = node.expression.arguments?.[0]
      const isEnumReference = arg !== undefined && (ts.isPropertyAccessExpression(arg) || ts.isElementAccessExpression(arg))
      if (!isEnumReference) {
        lines.push(sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1)
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return lines
}

function listEnumMemberNames(filePath: string, enumName: string): string[] | null {
  const sf = readSourceFile(filePath)
  let names: string[] | null = null
  function visit(node: ts.Node) {
    if (names) return
    if (ts.isEnumDeclaration(node) && node.name.text === enumName) {
      names = node.members.map((m) => {
        const raw = m.name.getText(sf).trim()
        return raw.replace(/^['"`]|['"`]$/g, '')
      })
      return
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return names
}

interface MappingCall {
  line: number
  arity: number
}

// 전역 예외 필터 파일 판별 — @Catch() + ExceptionFilter 구현.
function isExceptionFilterFile(content: string): boolean {
  return /@Catch\s*\(/.test(content) && /ExceptionFilter/.test(content)
}

interface ResponseObjectLiteral {
  line: number
  keys: string[]
}

// 파일 내 모든 ObjectLiteralExpression 중 'statusCode' 키를 가진 것 — 에러 응답 후보로 간주하고
// 필드 구성을 검사한다. spread(...foo)로만 구성된 리터럴은 정적으로 키를 알 수 없어 스킵한다.
function inspectResponseObjectLiterals(filePath: string): ResponseObjectLiteral[] {
  const sf = readSourceFile(filePath)
  const results: ResponseObjectLiteral[] = []
  function visit(node: ts.Node) {
    if (ts.isObjectLiteralExpression(node)) {
      const keys = node.properties
        .map((p) => (p.name && ts.isIdentifier(p.name) ? p.name.text : null))
        .filter((k): k is string => k !== null)
      if (keys.includes('statusCode')) {
        results.push({ line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1, keys })
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return results
}

function inspectGenerateErrorResponseCalls(filePath: string): MappingCall[] {
  const sf = readSourceFile(filePath)
  const results: MappingCall[] = []
  function visit(node: ts.Node) {
    if (
      ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && node.expression.text === 'generateErrorResponse'
      && node.arguments.length >= 2
      && ts.isArrayLiteralExpression(node.arguments[1])
    ) {
      for (const el of node.arguments[1].elements) {
        if (ts.isArrayLiteralExpression(el)) {
          const line = sf.getLineAndCharacterOfPosition(el.getStart(sf)).line + 1
          results.push({ line, arity: el.elements.length })
        }
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return results
}

export function evaluateErrorHandling(root: string): EvaluatorResult {
  const failures: EvaluatorFailure[] = []
  let score = 25

  const srcDir = path.join(root, 'src')
  const files = walkTsFiles(srcDir)
  const rel = (f: string) => path.relative(root, f)

  for (const file of files) {
    const content = fs.readFileSync(file, 'utf-8')

    if (file.includes('/domain/') && content.includes('HttpException')) {
      failures.push({
        ruleId: 'checklist.step7.domain.no-http-exception',
        severity: 'high',
        message: rel(file),
        docRef: DOC_REF_BASE
      })
      score -= 8
    }

    // 타입화된 에러만 허용(AGENTS.md "에러는 enum으로 타입화 — free-form 문자열 금지") —
    // Domain과 Application 레이어 둘 다 <Domain>ErrorMessage enum 참조를 강제한다
    // (error-handling.md "Domain / Service — plain Error throw" 섹션 참고). ruleId는 레이어별로 분리.
    if (file.includes('/application/') || file.includes('/domain/')) {
      const layerLabel = file.includes('/domain/') ? 'domain' : 'application'
      for (const line of findGenericErrorThrows(file)) {
        failures.push({
          ruleId: `checklist.step7.${layerLabel}.no-generic-error`,
          severity: 'medium',
          message: `${rel(file)}:${line} — throw new Error()의 인자는 <Domain>ErrorMessage enum 참조여야 함 (raw 문자열 금지)`,
          docRef: DOC_REF_BASE
        })
        score -= 5
      }
    }

    // 전역 예외 필터가 구성하는 에러 응답 객체가 정확히 4개 필드(statusCode/code/message/error)인지 확인.
    if (isExceptionFilterFile(content)) {
      for (const obj of inspectResponseObjectLiterals(file)) {
        const missing = RESPONSE_SCHEMA_FIELDS.filter((k) => !obj.keys.includes(k))
        const extra = obj.keys.filter((k) => !RESPONSE_SCHEMA_FIELDS.includes(k))
        if (missing.length > 0 || extra.length > 0) {
          const detail = [
            missing.length > 0 ? `누락: ${missing.join(', ')}` : null,
            extra.length > 0 ? `불필요한 필드: ${extra.join(', ')}` : null
          ].filter(Boolean).join(' / ')
          failures.push({
            ruleId: 'error-handling.response-schema.field-mismatch',
            severity: 'high',
            message: `${rel(file)}:${obj.line} — 에러 응답 객체가 정확히 4개 필드(statusCode, code, message, error)를 갖지 않음 (${detail})`,
            docRef: DOC_REF_RESPONSE_SCHEMA
          })
          score -= penaltyFor('high')
        }
      }
    }
  }

  const errorMessageFiles = files.filter((f) => /-error-message\.ts$/.test(path.basename(f)))
  for (const emFile of errorMessageFiles) {
    const base = path.basename(emFile)
    const match = base.match(/^(.+)-error-message\.ts$/)
    if (!match) continue
    const kebabDomain = match[1]
    const pascalDomain = kebabToPascal(kebabDomain)
    const dir = path.dirname(emFile)
    const codeFile = path.join(dir, `${kebabDomain}-error-code.ts`)

    if (!fs.existsSync(codeFile)) {
      failures.push({
        ruleId: 'error-handling.error-code.file-missing',
        severity: 'high',
        message: `${rel(emFile)}에 대응하는 ${kebabDomain}-error-code.ts 파일이 없음`,
        docRef: DOC_REF_ERROR_CODE
      })
      score -= 4
      continue
    }

    const messageMembers = listEnumMemberNames(emFile, `${pascalDomain}ErrorMessage`)
    const codeMembers = listEnumMemberNames(codeFile, `${pascalDomain}ErrorCode`)

    if (messageMembers && codeMembers && messageMembers.length !== codeMembers.length) {
      failures.push({
        ruleId: 'error-handling.error-code.enum-count-mismatch',
        severity: 'medium',
        message: `${pascalDomain}ErrorMessage(${messageMembers.length}) vs ${pascalDomain}ErrorCode(${codeMembers.length}) 항목 수 불일치: ${rel(codeFile)}`,
        docRef: DOC_REF_ERROR_CODE
      })
      score -= 2
    }

    if (codeMembers) {
      for (const key of codeMembers) {
        if (!/^[A-Z][A-Z0-9_]*$/.test(key)) {
          failures.push({
            ruleId: 'error-handling.error-code.naming',
            severity: 'low',
            message: `${pascalDomain}ErrorCode.${key}는 SCREAMING_SNAKE_CASE가 아님: ${rel(codeFile)}`,
            docRef: DOC_REF_ERROR_CODE
          })
          score -= 1
        }
      }
    }
  }

  for (const file of files) {
    const content = fs.readFileSync(file, 'utf-8')
    if (!content.includes('generateErrorResponse')) continue
    for (const call of inspectGenerateErrorResponseCalls(file)) {
      if (call.arity !== 3) {
        failures.push({
          ruleId: 'error-handling.generate-error-response.tuple-arity',
          severity: 'high',
          message: `generateErrorResponse 매핑이 [메시지, 예외, 에러 코드] 3-튜플이 아님 (길이=${call.arity}): ${rel(file)}:${call.line}`,
          docRef: DOC_REF_CATCH
        })
        score -= 4
      }
    }
  }

  return {
    name: 'error-handling',
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  }
}
