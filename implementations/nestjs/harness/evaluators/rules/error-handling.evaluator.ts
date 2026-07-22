// The error-handling evaluator — Domain/Application layer rules + error code rules.
//
// Rules:
// - Referencing HttpException in a Domain layer file is prohibited.
// - `throw new Error()` in a Domain/Application layer file is prohibited (enforces referencing
//   an ErrorMessage enum — AGENTS.md's "type errors as an enum — free-form strings are prohibited").
// - If <domain>-error-message.ts exists, <domain>-error-code.ts also exists in the same directory.
// - The number of entries in the <Domain>ErrorMessage and <Domain>ErrorCode enums match 1:1.
// - <Domain>ErrorCode enum keys are SCREAMING_SNAKE_CASE.
// - Each tuple in generateErrorResponse's mapping array is a 3-tuple [message, ExceptionClass, ErrorCode].
// - The error response object constructed by the global exception filter (@Catch() +
//   ExceptionFilter) must have exactly these 4 fields: { statusCode, code, message, error }.

import * as fs from 'node:fs'
import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { readSourceFile, walkTsFiles } from '../shared/ast-utils'
import { penaltyFor } from '../shared/penalty'

const DOC_REF_BASE = 'docs/architecture/error-handling.md'
const DOC_REF_ERROR_CODE = `${DOC_REF_BASE}#error-codes--defined-as-an-enum-11-mapped-with-the-message`
const DOC_REF_CATCH = `${DOC_REF_BASE}#controller--catch-and-rethrow`
const DOC_REF_RESPONSE_SCHEMA = `${DOC_REF_BASE}#error-response-format--the-standard-json-structure`

// The exact 4 fields the error response format (error-handling.md) requires.
const RESPONSE_SCHEMA_FIELDS = ['statusCode', 'code', 'message', 'error']

function kebabToPascal(kebab: string): string {
  return kebab
    .split('-')
    .filter(Boolean)
    .map((s) => s[0].toUpperCase() + s.slice(1))
    .join('')
}

// Among `throw new Error(...)` calls, only flags a violation when the argument isn't a
// <Domain>ErrorMessage enum reference (PropertyAccess/ElementAccess) — a raw string literal, a
// template literal, no argument, etc. `throw new Error(ErrorMessage['...'])` is the normal
// pattern the guide requires, so it never matches.
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

// Detects a global exception filter file — implements @Catch() + ExceptionFilter.
function isExceptionFilterFile(content: string): boolean {
  return /@Catch\s*\(/.test(content) && /ExceptionFilter/.test(content)
}

interface ResponseObjectLiteral {
  line: number
  keys: string[]
}

// Among every ObjectLiteralExpression in the file, the ones with a 'statusCode' key — treated
// as an error-response candidate and its field composition is checked. A literal made up only
// of a spread (...foo) is skipped since its keys can't be known statically.
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

    // Only typed errors are allowed (AGENTS.md's "type errors as an enum — free-form strings
    // are prohibited") — enforces a <Domain>ErrorMessage enum reference in both the Domain and
    // Application layers (see error-handling.md's "Domain / Service — throw a plain Error"
    // section). The ruleId is split per layer.
    if (file.includes('/application/') || file.includes('/domain/')) {
      const layerLabel = file.includes('/domain/') ? 'domain' : 'application'
      for (const line of findGenericErrorThrows(file)) {
        failures.push({
          ruleId: `checklist.step7.${layerLabel}.no-generic-error`,
          severity: 'medium',
          message: `${rel(file)}:${line} — the argument to throw new Error() must be a <Domain>ErrorMessage enum reference (a raw string is forbidden)`,
          docRef: DOC_REF_BASE
        })
        score -= 5
      }
    }

    // Confirms the error response object the global exception filter constructs has exactly 4 fields (statusCode/code/message/error).
    if (isExceptionFilterFile(content)) {
      for (const obj of inspectResponseObjectLiterals(file)) {
        const missing = RESPONSE_SCHEMA_FIELDS.filter((k) => !obj.keys.includes(k))
        const extra = obj.keys.filter((k) => !RESPONSE_SCHEMA_FIELDS.includes(k))
        if (missing.length > 0 || extra.length > 0) {
          const detail = [
            missing.length > 0 ? `missing: ${missing.join(', ')}` : null,
            extra.length > 0 ? `unnecessary fields: ${extra.join(', ')}` : null
          ].filter(Boolean).join(' / ')
          failures.push({
            ruleId: 'error-handling.response-schema.field-mismatch',
            severity: 'high',
            message: `${rel(file)}:${obj.line} — the error response object does not have exactly 4 fields (statusCode, code, message, error) (${detail})`,
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
        message: `No ${kebabDomain}-error-code.ts file corresponding to ${rel(emFile)} was found`,
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
        message: `${pascalDomain}ErrorMessage(${messageMembers.length}) vs ${pascalDomain}ErrorCode(${codeMembers.length}) member count mismatch: ${rel(codeFile)}`,
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
            message: `${pascalDomain}ErrorCode.${key} is not SCREAMING_SNAKE_CASE: ${rel(codeFile)}`,
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
          message: `A generateErrorResponse mapping is not a [message, exception, error code] 3-tuple (length=${call.arity}): ${rel(file)}:${call.line}`,
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
