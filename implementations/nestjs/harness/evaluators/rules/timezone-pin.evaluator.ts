// The timezone-pin evaluator — the bootstrap must pin the process timezone to UTC
// (guide: docs/conventions.md — "Timezone rule — store UTC", docs/architecture/bootstrap.md).
//
// Why this is a rule at all: the timestamp columns are `TIMESTAMP` — WITHOUT TIME ZONE. The pg
// driver serializes a JS Date using the process's local UTC offset, and a WITHOUT TIME ZONE
// column then discards that offset and keeps only the wall clock. So the instant that reaches
// the database is decided by the HOST's timezone — UTC on CI, KST on a laptop in Seoul — and no
// amount of care at the call site can fix it, because a JS Date is already an absolute instant
// with nothing to "convert". The only place the divergence can be closed is the process's own
// timezone, which is why the check is on the bootstrap rather than on persistence code.
//
// Check: starting from src/main.ts, follow its import specifiers ONE level into the project's
// own src/ tree and look for an assignment of the form `process.env.TZ = '<literal>'` (the
// bracket form `process.env['TZ']` counts too).
//   - No such assignment anywhere in that set               -> timezone-pin.missing
//   - Assigned a zone other than UTC                        -> timezone-pin.non-utc
//   - Assigned UTC, but not by the module main.ts imports
//     FIRST (including an assignment written inline in
//     main.ts, which module-loading hoists every import
//     above and so cannot be first either)                  -> timezone-pin.not-first-import
//
// Only a string literal is inspected. A value read from config or computed at runtime cannot be
// statically proven wrong, so it is left alone rather than reported as a false positive.
//
// Applicability: skipped entirely when src/main.ts is absent — the same gate
// bootstrap-healthcheck uses, so a submission with a different entrypoint is not penalised.

import * as fs from 'node:fs'
import * as path from 'node:path'
import ts from 'typescript'

import { EvaluatorFailure, EvaluatorResult } from '../shared/types'
import { penaltyFor } from '../shared/penalty'
import { readSourceFile, resolveImportPath } from '../shared/ast-utils'

const CONVENTIONS_DOC_REF = 'docs/conventions.md'
const BOOTSTRAP_DOC_REF = 'docs/architecture/bootstrap.md'

// Zone names that mean UTC. Anything else is a real zone (or an alias for one) and would make
// storage depend on where the process runs.
const UTC_ZONE_NAMES = new Set(['UTC', 'Etc/UTC', 'Etc/GMT', 'GMT'])

interface Pin {
  /** Absolute path of the file holding the assignment. */
  file: string
  /** The assigned string literal, e.g. 'UTC'. */
  zone: string
}

/** Resolves an import specifier to a real file inside the project's src/ tree, or null. */
function resolveProjectFile(root: string, fromFile: string, specifier: string): string | null {
  const base = resolveImportPath(root, fromFile, specifier)
  if (!base) return null

  for (const candidate of [`${base}.ts`, path.join(base, 'index.ts')]) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate
  }
  return null
}

/** main.ts's import specifiers, in source order. */
function importSpecifiers(sf: ts.SourceFile): string[] {
  const specifiers: string[] = []
  sf.forEachChild((node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
      specifiers.push(node.moduleSpecifier.text)
    }
  })
  return specifiers
}

/** True for `process.env.TZ` and `process.env['TZ']`. */
function isProcessEnvTz(node: ts.Expression, sf: ts.SourceFile): boolean {
  if (ts.isPropertyAccessExpression(node)) {
    return node.name.text === 'TZ' && node.expression.getText(sf) === 'process.env'
  }
  if (ts.isElementAccessExpression(node)) {
    const arg = node.argumentExpression
    return ts.isStringLiteral(arg) && arg.text === 'TZ' && node.expression.getText(sf) === 'process.env'
  }
  return false
}

/** The first `process.env.TZ = '<literal>'` assignment in a file, if any. */
function findPin(file: string): Pin | null {
  const sf = readSourceFile(file)
  let pin: Pin | null = null

  function visit(node: ts.Node): void {
    if (
      pin === null
      && ts.isBinaryExpression(node)
      && node.operatorToken.kind === ts.SyntaxKind.EqualsToken
      && isProcessEnvTz(node.left, sf)
      && ts.isStringLiteral(node.right)
    ) {
      pin = { file, zone: node.right.text }
      return
    }
    if (pin === null) ts.forEachChild(node, visit)
  }
  visit(sf)

  return pin
}

export function evaluateTimezonePin(root: string): EvaluatorResult {
  const mainPath = path.join(root, 'src', 'main.ts')
  if (!fs.existsSync(mainPath)) {
    return { name: 'timezone-pin', score: 0, maxScore: 0, failures: [] }
  }

  const rel = (file: string) => path.relative(root, file).replace(/\\/g, '/')
  const failures: EvaluatorFailure[] = []

  const mainSource = readSourceFile(mainPath)
  const specifiers = importSpecifiers(mainSource)

  // Every project-internal module main.ts imports, in source order — the search space for the
  // pin, since it may have been put in any of them.
  const importedFiles = specifiers
    .map((specifier) => resolveProjectFile(root, mainPath, specifier))
    .filter((file): file is string => file !== null)

  // The one module allowed to hold the pin: whatever the LITERAL first import resolves to. A
  // bare package specifier resolves to null, which is the point — if main.ts imports a package
  // before anything else, that package's module graph has already run and no project module can
  // still be first, so the pin is too late wherever it sits.
  const firstImportedFile = specifiers.length > 0
    ? resolveProjectFile(root, mainPath, specifiers[0])
    : null

  let pin: Pin | null = null
  for (const file of [mainPath, ...importedFiles]) {
    pin = findPin(file)
    if (pin) break
  }

  if (!pin) {
    failures.push({
      ruleId: 'timezone-pin.missing',
      severity: 'high',
      message:
        'src/main.ts — the bootstrap never pins the process timezone. Timestamp columns are '
        + 'TIMESTAMP (WITHOUT TIME ZONE), so the driver stores the host\'s wall clock and the '
        + "same write means a different instant per machine. Set process.env.TZ = 'UTC' in a "
        + 'side-effect module imported first by main.ts',
      docRef: CONVENTIONS_DOC_REF
    })
  } else if (!UTC_ZONE_NAMES.has(pin.zone)) {
    failures.push({
      ruleId: 'timezone-pin.non-utc',
      severity: 'critical',
      message:
        `${rel(pin.file)} — the process timezone is pinned to '${pin.zone}'. Timestamps are `
        + 'persisted as the UTC instant; pinning any other zone writes a shifted wall clock into '
        + "the TIMESTAMP (WITHOUT TIME ZONE) columns. Pin 'UTC'",
      docRef: CONVENTIONS_DOC_REF
    })
  } else if (pin.file !== firstImportedFile) {
    failures.push({
      ruleId: 'timezone-pin.not-first-import',
      severity: 'medium',
      message:
        `${rel(pin.file)} — the UTC pin is not applied by main.ts's first import`
        + `${firstImportedFile ? ` (that is ${rel(firstImportedFile)})` : ''}. Node applies a `
        + 'change to process.env.TZ only to date operations that follow it, and module loading '
        + 'hoists every import above any inline statement — so the pin must live in the module '
        + 'main.ts imports first, ahead of anything that can stamp a time or open a pool',
      docRef: BOOTSTRAP_DOC_REF
    })
  }

  let score = 10
  for (const failure of failures) score -= penaltyFor(failure.severity)

  return { name: 'timezone-pin', score: Math.max(score, 0), maxScore: 10, failures }
}
