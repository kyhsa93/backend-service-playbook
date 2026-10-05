"use strict";
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));

// evaluators/cli/run.ts
var fs37 = __toESM(require("node:fs"));
var path46 = __toESM(require("node:path"));

// evaluators/rules/layer-dependency.evaluator.ts
var fs = __toESM(require("node:fs"));
var path = __toESM(require("node:path"));
function walk(dir, files = []) {
  if (!fs.existsSync(dir)) return files;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, files);
    else if (full.endsWith(".ts")) files.push(full);
  }
  return files;
}
function evaluateLayerDependency(root) {
  const failures = [];
  let score = 25;
  const files = walk(path.join(root, "src"));
  for (const file of files) {
    const content = fs.readFileSync(file, "utf-8");
    if (file.includes("/domain/")) {
      if (content.includes("@nestjs") || content.includes("typeorm")) {
        failures.push({
          ruleId: "layer.domain.no-framework",
          severity: "high",
          message: `Framework dependency found in the domain layer: ${file}`
        });
        score -= 5;
      }
    }
    if (file.includes("/application/")) {
      if (content.includes("typeorm")) {
        failures.push({
          ruleId: "layer.application.no-direct-orm",
          severity: "high",
          message: `Direct ORM usage in the application layer: ${file}`
        });
        score -= 5;
      }
    }
  }
  return {
    name: "layer-dependency",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/repository-pattern.evaluator.ts
var fs3 = __toESM(require("node:fs"));
var path3 = __toESM(require("node:path"));
var import_typescript2 = __toESM(require("typescript"));

// evaluators/shared/ast-utils.ts
var fs2 = __toESM(require("node:fs"));
var path2 = __toESM(require("node:path"));
var import_typescript = __toESM(require("typescript"));
function walkTsFiles(dir, files = []) {
  if (!fs2.existsSync(dir)) return files;
  for (const entry of fs2.readdirSync(dir, { withFileTypes: true })) {
    const full = path2.join(dir, entry.name);
    if (entry.isDirectory()) walkTsFiles(full, files);
    else if (full.endsWith(".ts")) files.push(full);
  }
  return files;
}
function readSourceFile(filePath) {
  const source = fs2.readFileSync(filePath, "utf-8");
  return import_typescript.default.createSourceFile(filePath, source, import_typescript.default.ScriptTarget.Latest, true);
}
function parseImports(filePath) {
  const sf = readSourceFile(filePath);
  const imports = [];
  sf.forEachChild((node) => {
    if (import_typescript.default.isImportDeclaration(node) && import_typescript.default.isStringLiteral(node.moduleSpecifier)) {
      imports.push(node.moduleSpecifier.text);
    }
  });
  return imports;
}
function hasProviderArray(filePath) {
  const sf = readSourceFile(filePath);
  let found = false;
  function visit(node) {
    if (import_typescript.default.isPropertyAssignment(node) && node.name.getText(sf) === "providers") {
      found = true;
    }
    import_typescript.default.forEachChild(node, visit);
  }
  visit(sf);
  return found;
}
function classifyLayer(filePath) {
  const normalized = filePath.replace(/\\/g, "/");
  if (normalized.includes("/domain/")) return "domain";
  if (normalized.includes("/application/")) return "application";
  if (normalized.includes("/interface/")) return "interface";
  if (normalized.includes("/infrastructure/")) return "infrastructure";
  return "unknown";
}
function resolveImportPath(root, fromFile, specifier) {
  if (specifier.startsWith("@/")) return path2.join(root, "src", specifier.slice(2));
  if (specifier.startsWith(".")) return path2.resolve(path2.dirname(fromFile), specifier);
  return null;
}
function domainSegment(root, filePath) {
  const rel = path2.relative(path2.join(root, "src"), filePath).replace(/\\/g, "/");
  const [first] = rel.split("/");
  return first && !first.startsWith("..") ? first : null;
}
function isDomainBearing(root, domain) {
  return fs2.existsSync(path2.join(root, "src", domain, "domain"));
}
function listMethodDecorators(filePath) {
  const sf = readSourceFile(filePath);
  const results = [];
  function visit(node) {
    if (import_typescript.default.isMethodDeclaration(node) && node.name && import_typescript.default.isIdentifier(node.name)) {
      const decorators = [];
      const mods = node.modifiers ?? [];
      for (const mod of mods) {
        if (!import_typescript.default.isDecorator(mod)) continue;
        const expr = mod.expression;
        let name;
        let argsText = "";
        if (import_typescript.default.isCallExpression(expr)) {
          name = expr.expression.getText(sf);
          argsText = expr.arguments.map((a) => a.getText(sf)).join(", ");
        } else {
          name = expr.getText(sf);
        }
        decorators.push({ name, argsText, fullText: mod.getText(sf) });
      }
      const body = node.body ? node.body.getText(sf).slice(1, -1) : "";
      results.push({ methodName: node.name.text, decorators, body });
    }
    import_typescript.default.forEachChild(node, visit);
  }
  visit(sf);
  return results;
}
function listConstructorParams(filePath) {
  const sf = readSourceFile(filePath);
  const params = [];
  function visit(node) {
    if (import_typescript.default.isConstructorDeclaration(node)) {
      for (const p of node.parameters) {
        if (!p.name || !import_typescript.default.isIdentifier(p.name)) continue;
        const typeText = p.type ? p.type.getText(sf) : "unknown";
        params.push({ name: p.name.text, typeText });
      }
    }
    import_typescript.default.forEachChild(node, visit);
  }
  visit(sf);
  return params;
}
function findClassDecorator(filePath, decoratorName) {
  const sf = readSourceFile(filePath);
  let found = null;
  function visit(node) {
    if (import_typescript.default.isClassDeclaration(node)) {
      const mods = node.modifiers ?? [];
      for (const mod of mods) {
        if (!import_typescript.default.isDecorator(mod)) continue;
        const expr = mod.expression;
        const exprName = import_typescript.default.isCallExpression(expr) ? expr.expression.getText(sf) : expr.getText(sf);
        if (exprName === decoratorName) {
          found = mod.getText(sf);
          return;
        }
      }
    }
    if (!found) import_typescript.default.forEachChild(node, visit);
  }
  visit(sf);
  return found;
}

// evaluators/rules/repository-pattern.evaluator.ts
var REPOSITORY_IMPL_NAME = /Repository(Impl)?$/;
function instantiatesRepositoryDirectly(filePath) {
  const sf = readSourceFile(filePath);
  let found = false;
  function visit(node) {
    if (found) return;
    if (import_typescript2.default.isNewExpression(node) && import_typescript2.default.isIdentifier(node.expression) && REPOSITORY_IMPL_NAME.test(node.expression.text)) {
      found = true;
      return;
    }
    import_typescript2.default.forEachChild(node, visit);
  }
  visit(sf);
  return found;
}
function importsTypeormDirectly(filePath) {
  return parseImports(filePath).some((spec) => spec === "typeorm" || spec.startsWith("typeorm/"));
}
function evaluateRepositoryPattern(root) {
  const failures = [];
  let score = 25;
  const domainPath = path3.join(root, "src");
  function walk4(dir) {
    if (!fs3.existsSync(dir)) return [];
    return fs3.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path3.join(dir, entry.name);
      return entry.isDirectory() ? walk4(full) : [full];
    });
  }
  const files = walk4(domainPath).filter((f) => f.endsWith(".ts"));
  for (const file of files) {
    if (file.endsWith("-repository.ts")) {
      const content = fs3.readFileSync(file, "utf-8");
      if (!content.includes("abstract class")) {
        failures.push({
          ruleId: "repository.abstract-class",
          severity: "high",
          message: `The repository must be an abstract class: ${file}`
        });
        score -= 5;
      }
    }
    if (file.includes("/application/")) {
      if (instantiatesRepositoryDirectly(file) || importsTypeormDirectly(file)) {
        failures.push({
          ruleId: "repository.no-direct-instantiation",
          severity: "high",
          message: `Directly instantiating a repository in application is forbidden: ${file}`
        });
        score -= 5;
      }
    }
  }
  return {
    name: "repository-pattern",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/repository-naming.evaluator.ts
var path4 = __toESM(require("node:path"));
var import_typescript3 = __toESM(require("typescript"));

// evaluators/shared/penalty.ts
var BASE_PENALTY = {
  critical: 6,
  high: 4,
  medium: 2,
  low: 1
};
function penaltyFor(severity, weight = 1) {
  return BASE_PENALTY[severity] * weight;
}

// evaluators/rules/repository-naming.evaluator.ts
var DOC = "docs/architecture/repository-pattern.md";
var DOC_REF = `${DOC}#repository-method-naming-rules`;
function isRepositoryDomainFile(root, file) {
  const rel = path4.relative(root, file).replace(/\\/g, "/");
  return /^src\/[^/]+\/domain\/[^/]+-repository\.ts$/.test(rel);
}
function extractAbstractRepositoryMethods(filePath) {
  const sf = readSourceFile(filePath);
  const methods = [];
  function visit(node) {
    if (import_typescript3.default.isClassDeclaration(node) && node.name) {
      const classMods = node.modifiers ?? [];
      const isAbstractClass = classMods.some((m) => m.kind === import_typescript3.default.SyntaxKind.AbstractKeyword);
      const isRepositoryClass = /Repository$/.test(node.name.text);
      if (isAbstractClass && isRepositoryClass) {
        for (const member of node.members) {
          if (!import_typescript3.default.isMethodDeclaration(member) || !member.name || !import_typescript3.default.isIdentifier(member.name)) continue;
          const memberMods = member.modifiers ?? [];
          const isAbstractMethod = memberMods.some((m) => m.kind === import_typescript3.default.SyntaxKind.AbstractKeyword);
          if (isAbstractMethod) methods.push({ name: member.name.text });
        }
      }
    }
    import_typescript3.default.forEachChild(node, visit);
  }
  visit(sf);
  return methods;
}
var ANTI_PATTERNS = [
  {
    ruleId: "repository-naming.find-by-shape",
    test: (name) => /^findBy([A-Z]|$)/.test(name),
    describe: (name) => `${name}: find...By... form is forbidden. Conditions must be received as a single query object parameter to find<Noun>s(query) (e.g. findAccounts({ accountId, ownerId }))`
  },
  {
    ruleId: "repository-naming.find-all-bare",
    test: (name) => name === "findAll",
    describe: () => `findAll: a bare findAll is forbidden. Name it find<Noun>s, including the domain noun (e.g. findAccounts)`
  },
  {
    ruleId: "repository-naming.count-method",
    test: (name) => /^count/.test(name),
    describe: (name) => `${name}: a separate count method is forbidden. find<Noun>s must return the count together in an { items, count } shape`
  },
  {
    ruleId: "repository-naming.save-bare",
    test: (name) => name === "save",
    describe: () => `save: a bare save is forbidden. Name it save<Noun>, including the domain noun (e.g. saveAccount)`
  },
  {
    ruleId: "repository-naming.delete-bare",
    test: (name) => name === "delete",
    describe: () => `delete: a bare delete is forbidden. Name it delete<Noun>, including the domain noun (e.g. deleteAccount)`
  },
  {
    ruleId: "repository-naming.update-method",
    test: (name) => /^update([A-Z]|$)/.test(name),
    describe: (name) => `${name}: a separate update method is forbidden. Load it, change state via the Aggregate's domain method, and save it with save<Noun>`
  }
];
function evaluateRepositoryNaming(root) {
  const srcRoot = path4.join(root, "src");
  const files = walkTsFiles(srcRoot).filter((f) => isRepositoryDomainFile(root, f));
  if (files.length === 0) {
    return { name: "repository-naming", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path4.relative(root, f);
  for (const file of files) {
    const methods = extractAbstractRepositoryMethods(file);
    for (const method of methods) {
      const antiPattern = ANTI_PATTERNS.find((p) => p.test(method.name));
      if (!antiPattern) continue;
      failures.push({
        ruleId: antiPattern.ruleId,
        severity: "high",
        message: `${rel(file)} \u2014 ${antiPattern.describe(method.name)}`,
        docRef: DOC_REF
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "repository-naming",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/controller-path.evaluator.ts
var fs4 = __toESM(require("node:fs"));
var path5 = __toESM(require("node:path"));
var VERB_PREFIXES = ["create", "get", "update", "delete", "set", "add", "remove"];
function evaluateControllerPath(root) {
  const failures = [];
  let score = 25;
  function walk4(dir) {
    if (!fs4.existsSync(dir)) return [];
    return fs4.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path5.join(dir, entry.name);
      return entry.isDirectory() ? walk4(full) : [full];
    });
  }
  const files = walk4(path5.join(root, "src")).filter((f) => f.endsWith("controller.ts"));
  for (const file of files) {
    const content = fs4.readFileSync(file, "utf-8");
    for (const verb of VERB_PREFIXES) {
      if (content.includes(`@Controller('${verb}`)) {
        failures.push({
          ruleId: "controller.path.no-verb-prefix",
          severity: "medium",
          message: `Verb-form paths are forbidden (${verb}): ${file}`
        });
        score -= 5;
        break;
      }
    }
  }
  return {
    name: "controller-path",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/checklist.evaluator.ts
var fs5 = __toESM(require("node:fs"));
var path6 = __toESM(require("node:path"));
function locateChecklistDoc() {
  const candidates = [
    path6.resolve(process.cwd(), "docs/checklist.md"),
    path6.resolve(process.cwd(), "../docs/checklist.md"),
    path6.resolve(process.cwd(), "../../docs/checklist.md"),
    path6.resolve(process.cwd(), "../../../docs/checklist.md")
  ];
  for (const c of candidates) {
    if (fs5.existsSync(c)) return c;
  }
  return null;
}
function walk2(dir, files = []) {
  if (!fs5.existsSync(dir)) return files;
  for (const entry of fs5.readdirSync(dir, { withFileTypes: true })) {
    const full = path6.join(dir, entry.name);
    if (entry.isDirectory()) walk2(full, files);
    else if (full.endsWith(".ts")) files.push(full);
  }
  return files;
}
function parseChecklistSteps() {
  const docPath = locateChecklistDoc();
  if (!docPath) return [];
  const md = fs5.readFileSync(docPath, "utf-8");
  const lines = md.split("\n");
  const steps = [];
  let current = null;
  const stepHeader = /^## STEP (\d+)\s*—\s*(.+)$/;
  for (const line of lines) {
    const m = line.match(stepHeader);
    if (m) {
      if (current) steps.push(current);
      current = { number: Number(m[1]), title: m[2].trim(), itemCount: 0 };
      continue;
    }
    if (current && /^\[ \]/.test(line)) current.itemCount += 1;
  }
  if (current) steps.push(current);
  return steps;
}
function stepTitle(steps, n) {
  return steps.find((s) => s.number === n)?.title ?? `STEP ${n}`;
}
function evaluateChecklist(root) {
  const failures = [];
  let score = 100;
  const steps = parseChecklistSteps();
  const files = walk2(path6.join(root, "src"));
  const rel = (f) => path6.relative(root, f);
  const push = (ruleId, severity, message, penalty) => {
    failures.push({ ruleId, severity, message });
    score -= penalty;
  };
  for (const file of files) {
    const content = fs5.readFileSync(file, "utf-8");
    const layer = file.includes("/domain/") ? "domain" : file.includes("/application/") ? "application" : file.includes("/interface/") ? "interface" : file.includes("/infrastructure/") ? "infrastructure" : "unknown";
    if (layer === "domain") {
      if (content.includes("@Injectable(")) {
        push(
          "checklist.step2.domain.no-nest-decorator",
          "high",
          `${stepTitle(steps, 2)} \u2014 Domain uses @Injectable(): ${rel(file)}`,
          8
        );
      }
      if (/from\s+['"]class-validator['"]/.test(content) || /from\s+['"]class-transformer['"]/.test(content)) {
        push(
          "checklist.step2.domain.no-validator-import",
          "high",
          `${stepTitle(steps, 2)} \u2014 Domain imports class-validator/class-transformer: ${rel(file)}`,
          6
        );
      }
      if (/@Entity\(/.test(content)) {
        push(
          "checklist.step2.domain.no-typeorm-entity",
          "high",
          `${stepTitle(steps, 2)} \u2014 Domain has an @Entity() decorator (TypeORM leak): ${rel(file)}`,
          8
        );
      }
      if (/\bLogger\b/.test(content) && /from\s+['"]@nestjs\/common['"]/.test(content)) {
        push(
          "checklist.step2.domain.no-logger",
          "medium",
          `${stepTitle(steps, 2)} \u2014 Domain uses the NestJS Logger (logging belongs in Application): ${rel(file)}`,
          4
        );
      }
    }
    if (layer === "application") {
      if (content.includes("HttpException")) {
        push(
          "checklist.step3.application.no-http-exception",
          "high",
          `${stepTitle(steps, 3)} \u2014 Application uses HttpException: ${rel(file)}`,
          8
        );
      }
      if (/from\s+['"]@aws-sdk\//.test(content)) {
        push(
          "checklist.step3.application.no-aws-sdk",
          "medium",
          `${stepTitle(steps, 3)} \u2014 Application imports the AWS SDK directly: ${rel(file)}`,
          5
        );
      }
      if (/from\s+['"][^'"]*-repository-impl['"]/.test(content)) {
        push(
          "checklist.step3.application.no-impl-import",
          "high",
          `${stepTitle(steps, 3)} \u2014 Application imports an -impl directly (must go through the abstract class): ${rel(file)}`,
          6
        );
      }
    }
    if (/-impl\.ts$/.test(file) && !file.includes("/infrastructure/")) {
      push(
        "checklist.step4.impl-outside-infrastructure",
        "medium",
        `${stepTitle(steps, 4)} \u2014 *-impl.ts is located outside infrastructure/: ${rel(file)}`,
        4
      );
    }
    const controllerCount = (content.match(/@Controller\s*\(/g) ?? []).length;
    if (controllerCount > 1) {
      push(
        "checklist.step5.interface.single-controller-per-file",
        "medium",
        `${stepTitle(steps, 5)} \u2014 ${controllerCount} @Controller decorators in a single file: ${rel(file)}`,
        3
      );
    }
    if (/-module\.ts$/.test(file) && (layer === "application" || layer === "interface" || layer === "infrastructure")) {
      push(
        "checklist.step5.module-placement",
        "medium",
        `${stepTitle(steps, 5)} \u2014 the Module file is located inside ${layer}/ (recommended at the domain root): ${rel(file)}`,
        3
      );
    }
    if (/\.entity\.ts$/.test(file) && layer !== "unknown" && !file.includes("/infrastructure/") && !file.includes("/database/")) {
      if (path6.basename(file) !== "base.entity.ts") {
        push(
          "checklist.step12.entity-placement",
          "medium",
          `${stepTitle(steps, 12)} \u2014 *.entity.ts is located outside infrastructure/: ${rel(file)}`,
          3
        );
      }
    }
    if (/-query-service\.ts$/.test(file) && /\bRepository\b/.test(content) && !/\bQuery\b/.test(content)) {
      push(
        "checklist.step3.query-service-uses-repository",
        "medium",
        `${stepTitle(steps, 3)} \u2014 the Query Service uses a Repository (must use the Query interface instead): ${rel(file)}`,
        4
      );
    }
    if (/synchronize\s*:\s*true(?![^,})]*process\.env)/.test(content)) {
      push(
        "checklist.step12.typeorm-synchronize-unconditional",
        "high",
        `${stepTitle(steps, 12)} \u2014 TypeORM synchronize: true is set unconditionally (risk of a production incident): ${rel(file)}`,
        6
      );
    }
    if (!file.endsWith(".spec.ts") && /(?:password|secret|apikey|api_key|token)\s*[:=]\s*['"][A-Za-z0-9_-]{8,}['"]/i.test(content)) {
      push(
        "checklist.step12.no-hardcoded-secret",
        "critical",
        `${stepTitle(steps, 12)} \u2014 suspected hardcoded secret (use process.env instead): ${rel(file)}`,
        8
      );
    }
    if (/\bTODO\b/.test(content)) {
      push(
        "checklist.step14.no-todo",
        "low",
        `${stepTitle(steps, 14)} \u2014 a leftover TODO comment remains: ${rel(file)}`,
        2
      );
    }
    const relImportCount = (content.match(/from\s+['"]\.\.\//g) ?? []).length;
    if (relImportCount >= 3) {
      push(
        "checklist.step14.avoid-relative-imports",
        "low",
        `${stepTitle(steps, 14)} \u2014 ${relImportCount} relative-path ('../') imports (an absolute @/ path is recommended): ${rel(file)}`,
        1
      );
    }
  }
  if (steps.length > 0) {
    const totalItems = steps.reduce((sum, s) => sum + s.itemCount, 0);
    failures.push({
      ruleId: "checklist.meta.coverage",
      severity: "low",
      message: `Parsed docs/checklist.md: ${steps.length} STEPs, ${totalItems} check items (the harness mechanically verifies only some of them)`
    });
  } else {
    failures.push({
      ruleId: "checklist.meta.doc-missing",
      severity: "medium",
      message: `docs/checklist.md not found \u2014 skipping STEP structure parsing`
    });
  }
  return { name: "checklist", score: Math.max(score, 0), maxScore: 100, failures };
}

// evaluators/rules/structure.evaluator.ts
var fs6 = __toESM(require("node:fs"));
var path7 = __toESM(require("node:path"));
function walkTs(dir, files = []) {
  if (!fs6.existsSync(dir)) return files;
  for (const entry of fs6.readdirSync(dir, { withFileTypes: true })) {
    const full = path7.join(dir, entry.name);
    if (entry.isDirectory()) walkTs(full, files);
    else if (full.endsWith(".ts")) files.push(full);
  }
  return files;
}
function anyFileMatches(files, pattern) {
  return files.some((f) => pattern.test(fs6.readFileSync(f, "utf-8")));
}
function evaluateStructure(root) {
  const failures = [];
  let score = 25;
  const required = ["domain", "application", "interface", "infrastructure"];
  const base = path7.join(root, "src");
  for (const dir of required) {
    const exists = fs6.existsSync(base) && fs6.readdirSync(base).some((d) => {
      const full = path7.join(base, d, dir);
      return fs6.existsSync(full);
    });
    if (!exists) {
      failures.push({
        ruleId: "structure.layer.missing",
        severity: "high",
        message: `missing layer directory: ${dir}`
      });
      score -= 6;
    }
  }
  const allFiles = walkTs(base);
  const usesTaskQueue = anyFileMatches(
    allFiles,
    /@TaskConsumer\s*\(|import\s+\{[^}]*TaskQueue[^}]*\}\s+from\s+['"][^'"]*task-queue[^'"]*['"]/
  );
  const taskQueueDir = path7.join(base, "task-queue");
  if (usesTaskQueue && !fs6.existsSync(taskQueueDir)) {
    failures.push({
      ruleId: "structure.task-queue.missing",
      severity: "high",
      message: `Task Queue is used (@TaskConsumer or a TaskQueue import was detected) but the shared src/task-queue/ module directory is missing`
    });
    score -= 4;
  }
  return {
    name: "structure",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/file-naming.evaluator.ts
var fs7 = __toESM(require("node:fs"));
var path8 = __toESM(require("node:path"));
function walk3(dir, collected = []) {
  if (!fs7.existsSync(dir)) return collected;
  for (const entry of fs7.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path8.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk3(fullPath, collected);
      continue;
    }
    collected.push(fullPath);
  }
  return collected;
}
function isKebabCaseFileName(fileName) {
  return /^[a-z0-9]+(?:-[a-z0-9]+)*\.[a-z0-9.]+$/.test(fileName);
}
function evaluateFileNaming(submissionRoot) {
  const srcRoot = path8.join(submissionRoot, "src");
  const failures = [];
  let score = 25;
  const files = walk3(srcRoot).filter((filePath) => filePath.endsWith(".ts"));
  for (const filePath of files) {
    const fileName = path8.basename(filePath);
    if (!isKebabCaseFileName(fileName)) {
      failures.push({
        ruleId: "checklist.step1.file-kebab-case",
        severity: "medium",
        message: `kebab-case naming rule violation: ${path8.relative(submissionRoot, filePath)}`
      });
      score -= 3;
    }
    if (fileName.endsWith(".service.ts")) {
      failures.push({
        ruleId: "checklist.step1.service-file-name",
        severity: "low",
        message: `Service file naming convention needs review: ${path8.relative(submissionRoot, filePath)}`
      });
      score -= 1;
    }
    if (fileName.endsWith(".module.ts") && !/^[a-z0-9-]+-module\.ts$/.test(fileName)) {
      failures.push({
        ruleId: "checklist.step1.module-file-name",
        severity: "medium",
        message: `Module file naming rule violation: ${path8.relative(submissionRoot, filePath)}`
      });
      score -= 2;
    }
  }
  return {
    name: "file-naming",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/cqrs-pattern.evaluator.ts
var fs8 = __toESM(require("node:fs"));
var path9 = __toESM(require("node:path"));
function evaluateCqrsPattern(root) {
  const failures = [];
  let score = 25;
  const files = walkTsFiles(path9.join(root, "src"));
  const hasCommandDir = files.some((file) => file.replace(/\\/g, "/").includes("/application/command/"));
  const hasQueryDir = files.some((file) => file.replace(/\\/g, "/").includes("/application/query/"));
  if (!hasCommandDir) {
    failures.push({
      ruleId: "checklist.step3.application.command-directory-missing",
      severity: "medium",
      message: "The application/command directory is missing"
    });
    score -= 8;
  }
  if (!hasQueryDir) {
    failures.push({
      ruleId: "checklist.step3.application.query-directory-missing",
      severity: "medium",
      message: "The application/query directory is missing"
    });
    score -= 8;
  }
  const queryServiceUsesRepository = files.filter((file) => file.replace(/\\/g, "/").includes("/application/query/")).some((file) => {
    const content = fs8.readFileSync(file, "utf-8");
    return content.includes("Repository");
  });
  if (queryServiceUsesRepository) {
    failures.push({
      ruleId: "checklist.step3.query.no-repository-direct-use",
      severity: "high",
      message: "Direct use of a Repository was detected in the query layer"
    });
    score -= 10;
  }
  return {
    name: "cqrs-pattern",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/error-handling.evaluator.ts
var fs9 = __toESM(require("node:fs"));
var path10 = __toESM(require("node:path"));
var import_typescript4 = __toESM(require("typescript"));
var DOC_REF_BASE = "docs/architecture/error-handling.md";
var DOC_REF_ERROR_CODE = `${DOC_REF_BASE}#error-codes--defined-as-an-enum-11-mapped-with-the-message`;
var DOC_REF_CATCH = `${DOC_REF_BASE}#controller--catch-and-rethrow`;
var DOC_REF_RESPONSE_SCHEMA = `${DOC_REF_BASE}#error-response-format--the-standard-json-structure`;
var RESPONSE_SCHEMA_FIELDS = ["statusCode", "code", "message", "error"];
function kebabToPascal(kebab) {
  return kebab.split("-").filter(Boolean).map((s) => s[0].toUpperCase() + s.slice(1)).join("");
}
function findGenericErrorThrows(filePath) {
  const sf = readSourceFile(filePath);
  const lines = [];
  function visit(node) {
    if (import_typescript4.default.isThrowStatement(node) && node.expression && import_typescript4.default.isNewExpression(node.expression) && import_typescript4.default.isIdentifier(node.expression.expression) && node.expression.expression.text === "Error") {
      const arg = node.expression.arguments?.[0];
      const isEnumReference = arg !== void 0 && (import_typescript4.default.isPropertyAccessExpression(arg) || import_typescript4.default.isElementAccessExpression(arg));
      if (!isEnumReference) {
        lines.push(sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1);
      }
    }
    import_typescript4.default.forEachChild(node, visit);
  }
  visit(sf);
  return lines;
}
function listEnumMemberNames(filePath, enumName) {
  const sf = readSourceFile(filePath);
  let names = null;
  function visit(node) {
    if (names) return;
    if (import_typescript4.default.isEnumDeclaration(node) && node.name.text === enumName) {
      names = node.members.map((m) => {
        const raw = m.name.getText(sf).trim();
        return raw.replace(/^['"`]|['"`]$/g, "");
      });
      return;
    }
    import_typescript4.default.forEachChild(node, visit);
  }
  visit(sf);
  return names;
}
function isExceptionFilterFile(content) {
  return /@Catch\s*\(/.test(content) && /ExceptionFilter/.test(content);
}
function inspectResponseObjectLiterals(filePath) {
  const sf = readSourceFile(filePath);
  const results = [];
  function visit(node) {
    if (import_typescript4.default.isObjectLiteralExpression(node)) {
      const keys = node.properties.map((p) => p.name && import_typescript4.default.isIdentifier(p.name) ? p.name.text : null).filter((k) => k !== null);
      if (keys.includes("statusCode")) {
        results.push({ line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1, keys });
      }
    }
    import_typescript4.default.forEachChild(node, visit);
  }
  visit(sf);
  return results;
}
function inspectGenerateErrorResponseCalls(filePath) {
  const sf = readSourceFile(filePath);
  const results = [];
  function visit(node) {
    if (import_typescript4.default.isCallExpression(node) && import_typescript4.default.isIdentifier(node.expression) && node.expression.text === "generateErrorResponse" && node.arguments.length >= 2 && import_typescript4.default.isArrayLiteralExpression(node.arguments[1])) {
      for (const el of node.arguments[1].elements) {
        if (import_typescript4.default.isArrayLiteralExpression(el)) {
          const line = sf.getLineAndCharacterOfPosition(el.getStart(sf)).line + 1;
          results.push({ line, arity: el.elements.length });
        }
      }
    }
    import_typescript4.default.forEachChild(node, visit);
  }
  visit(sf);
  return results;
}
function evaluateErrorHandling(root) {
  const failures = [];
  let score = 25;
  const srcDir = path10.join(root, "src");
  const files = walkTsFiles(srcDir);
  const rel = (f) => path10.relative(root, f);
  for (const file of files) {
    const content = fs9.readFileSync(file, "utf-8");
    if (file.includes("/domain/") && content.includes("HttpException")) {
      failures.push({
        ruleId: "checklist.step7.domain.no-http-exception",
        severity: "high",
        message: rel(file),
        docRef: DOC_REF_BASE
      });
      score -= 8;
    }
    if (file.includes("/application/") || file.includes("/domain/")) {
      const layerLabel = file.includes("/domain/") ? "domain" : "application";
      for (const line of findGenericErrorThrows(file)) {
        failures.push({
          ruleId: `checklist.step7.${layerLabel}.no-generic-error`,
          severity: "medium",
          message: `${rel(file)}:${line} \u2014 the argument to throw new Error() must be a <Domain>ErrorMessage enum reference (a raw string is forbidden)`,
          docRef: DOC_REF_BASE
        });
        score -= 5;
      }
    }
    if (isExceptionFilterFile(content)) {
      for (const obj of inspectResponseObjectLiterals(file)) {
        const missing = RESPONSE_SCHEMA_FIELDS.filter((k) => !obj.keys.includes(k));
        const extra = obj.keys.filter((k) => !RESPONSE_SCHEMA_FIELDS.includes(k));
        if (missing.length > 0 || extra.length > 0) {
          const detail = [
            missing.length > 0 ? `missing: ${missing.join(", ")}` : null,
            extra.length > 0 ? `unnecessary fields: ${extra.join(", ")}` : null
          ].filter(Boolean).join(" / ");
          failures.push({
            ruleId: "error-handling.response-schema.field-mismatch",
            severity: "high",
            message: `${rel(file)}:${obj.line} \u2014 the error response object does not have exactly 4 fields (statusCode, code, message, error) (${detail})`,
            docRef: DOC_REF_RESPONSE_SCHEMA
          });
          score -= penaltyFor("high");
        }
      }
    }
  }
  const errorMessageFiles = files.filter((f) => /-error-message\.ts$/.test(path10.basename(f)));
  for (const emFile of errorMessageFiles) {
    const base = path10.basename(emFile);
    const match = base.match(/^(.+)-error-message\.ts$/);
    if (!match) continue;
    const kebabDomain = match[1];
    const pascalDomain = kebabToPascal(kebabDomain);
    const dir = path10.dirname(emFile);
    const codeFile = path10.join(dir, `${kebabDomain}-error-code.ts`);
    if (!fs9.existsSync(codeFile)) {
      failures.push({
        ruleId: "error-handling.error-code.file-missing",
        severity: "high",
        message: `No ${kebabDomain}-error-code.ts file corresponding to ${rel(emFile)} was found`,
        docRef: DOC_REF_ERROR_CODE
      });
      score -= 4;
      continue;
    }
    const messageMembers = listEnumMemberNames(emFile, `${pascalDomain}ErrorMessage`);
    const codeMembers = listEnumMemberNames(codeFile, `${pascalDomain}ErrorCode`);
    if (messageMembers && codeMembers && messageMembers.length !== codeMembers.length) {
      failures.push({
        ruleId: "error-handling.error-code.enum-count-mismatch",
        severity: "medium",
        message: `${pascalDomain}ErrorMessage(${messageMembers.length}) vs ${pascalDomain}ErrorCode(${codeMembers.length}) member count mismatch: ${rel(codeFile)}`,
        docRef: DOC_REF_ERROR_CODE
      });
      score -= 2;
    }
    if (codeMembers) {
      for (const key of codeMembers) {
        if (!/^[A-Z][A-Z0-9_]*$/.test(key)) {
          failures.push({
            ruleId: "error-handling.error-code.naming",
            severity: "low",
            message: `${pascalDomain}ErrorCode.${key} is not SCREAMING_SNAKE_CASE: ${rel(codeFile)}`,
            docRef: DOC_REF_ERROR_CODE
          });
          score -= 1;
        }
      }
    }
  }
  for (const file of files) {
    const content = fs9.readFileSync(file, "utf-8");
    if (!content.includes("generateErrorResponse")) continue;
    for (const call of inspectGenerateErrorResponseCalls(file)) {
      if (call.arity !== 3) {
        failures.push({
          ruleId: "error-handling.generate-error-response.tuple-arity",
          severity: "high",
          message: `A generateErrorResponse mapping is not a [message, exception, error code] 3-tuple (length=${call.arity}): ${rel(file)}:${call.line}`,
          docRef: DOC_REF_CATCH
        });
        score -= 4;
      }
    }
  }
  return {
    name: "error-handling",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/test-presence.evaluator.ts
var fs10 = __toESM(require("node:fs"));
var path11 = __toESM(require("node:path"));
function evaluateTestPresence(root) {
  const failures = [];
  let score = 25;
  const hasTestDir = fs10.existsSync(path11.join(root, "test"));
  const hasSpecFile = fs10.existsSync(path11.join(root, "src")) && fs10.readdirSync(path11.join(root, "src"), { recursive: true }).some((f) => f.toString().endsWith(".spec.ts"));
  if (!hasTestDir && !hasSpecFile) {
    failures.push({
      ruleId: "checklist.step15.tests.missing",
      severity: "high",
      message: "No test code exists"
    });
    score -= 10;
  }
  return {
    name: "test-presence",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/dto-validation.evaluator.ts
var fs11 = __toESM(require("node:fs"));
var path12 = __toESM(require("node:path"));
var REQUEST_DTO_SUFFIXES = ["-request-body.ts", "-request-querystring.ts", "-request-param.ts"];
function findExtendsBaseName(content) {
  const match = content.match(/class\s+\w+\s+extends\s+(\w+)/);
  return match ? match[1] : null;
}
function findImportModuleFor(content, name) {
  const importRegex = /import\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]/g;
  let m;
  while (m = importRegex.exec(content)) {
    const names = m[1].split(",").map((s) => s.trim());
    if (names.includes(name)) return m[2];
  }
  return null;
}
function resolveImportPath2(moduleSpecifier, fromFile, srcRoot) {
  const withExt = moduleSpecifier.endsWith(".ts") ? moduleSpecifier : `${moduleSpecifier}.ts`;
  if (moduleSpecifier.startsWith("@/")) return path12.join(srcRoot, withExt.slice(2));
  return path12.resolve(path12.dirname(fromFile), withExt);
}
function collectContentWithBases(filePath, srcRoot, depth = 0) {
  const content = fs11.readFileSync(filePath, "utf-8");
  if (depth >= 3) return content;
  const baseName = findExtendsBaseName(content);
  if (!baseName) return content;
  const modulePath = findImportModuleFor(content, baseName);
  if (!modulePath) return content;
  const resolved = resolveImportPath2(modulePath, filePath, srcRoot);
  if (!fs11.existsSync(resolved)) return content;
  return `${content}
${collectContentWithBases(resolved, srcRoot, depth + 1)}`;
}
function evaluateDtoValidation(root) {
  const failures = [];
  let score = 25;
  const srcRoot = path12.join(root, "src");
  const files = walkTsFiles(srcRoot).filter(
    (file) => file.split(path12.sep).includes("dto") && REQUEST_DTO_SUFFIXES.some((suffix) => file.endsWith(suffix))
  );
  for (const file of files) {
    const content = collectContentWithBases(file, srcRoot);
    if (!content.includes("@Is") && !content.includes("@Validate")) {
      failures.push({
        ruleId: "checklist.step6.dto.validation-missing",
        severity: "medium",
        message: file
      });
      score -= 5;
    }
  }
  return {
    name: "dto-validation",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/task-queue.evaluator.ts
var fs12 = __toESM(require("node:fs"));
var path13 = __toESM(require("node:path"));
function extractTaskTypeArg(argsText) {
  const m = argsText.match(/^\s*['"`]([^'"`]+)['"`]/);
  return m ? m[1] : null;
}
function findAppModuleFile(files) {
  const candidates = files.filter((f) => /app[-.]?module\.ts$/i.test(path13.basename(f)));
  for (const c of candidates) {
    if (findClassDecorator(c, "Module")) return c;
  }
  for (const f of files) {
    const content = fs12.readFileSync(f, "utf-8");
    if (findClassDecorator(f, "Module") && /class\s+AppModule\b/.test(content)) return f;
  }
  return null;
}
function evaluateTaskQueue(root) {
  const failures = [];
  const srcDir = path13.join(root, "src");
  const files = walkTsFiles(srcDir);
  const rel = (f) => path13.relative(root, f);
  const hasTaskQueueUsage = files.some((f) => {
    const content = fs12.readFileSync(f, "utf-8");
    return /@TaskConsumer\s*\(/.test(content) || /@Cron\s*\(/.test(content);
  });
  if (!hasTaskQueueUsage) {
    return { name: "task-queue", score: 0, maxScore: 0, failures: [] };
  }
  let score = 20;
  const taskTypesSeen = /* @__PURE__ */ new Map();
  let anyTaskConsumer = false;
  let anyCron = false;
  for (const file of files) {
    const content = fs12.readFileSync(file, "utf-8");
    const methods = listMethodDecorators(file);
    const fileHasTaskConsumer = methods.some((m) => m.decorators.some((d) => d.name === "TaskConsumer"));
    const fileHasCron = methods.some((m) => m.decorators.some((d) => d.name === "Cron"));
    if (fileHasTaskConsumer) anyTaskConsumer = true;
    if (fileHasCron) anyCron = true;
    for (const m of methods) {
      for (const d of m.decorators) {
        if (d.name !== "TaskConsumer") continue;
        const tt = extractTaskTypeArg(d.argsText);
        if (!tt) continue;
        const list = taskTypesSeen.get(tt) ?? [];
        list.push(rel(file));
        taskTypesSeen.set(tt, list);
      }
    }
    const layer = classifyLayer(file);
    if (fileHasTaskConsumer && layer !== "interface") {
      failures.push({
        ruleId: "task-queue.controller.layer",
        severity: "high",
        message: `The Task Controller (has @TaskConsumer) is located outside interface/, in the ${layer} layer: ${rel(file)}`,
        docRef: "docs/architecture/scheduling.md#taskcontroller--executing-commands-with-taskconsumer-methods-interface-layer"
      });
      score -= 5;
    }
    if (fileHasTaskConsumer && !/-task-controller\.ts$/.test(path13.basename(file))) {
      failures.push({
        ruleId: "task-queue.controller.file-suffix",
        severity: "medium",
        message: `A file with @TaskConsumer must follow the *-task-controller.ts naming format: ${rel(file)}`,
        docRef: "docs/architecture/scheduling.md#layer-placement"
      });
      score -= 2;
    }
    if (fileHasTaskConsumer) {
      const params = listConstructorParams(file);
      const hasDataSource = params.some((p) => /\bDataSource\b/.test(p.typeText));
      const hasRepository = params.some((p) => /\bRepository<.+>/.test(p.typeText));
      const hasExecLog = params.some((p) => /\bTaskExecutionLog\b/.test(p.typeText));
      const hasCommandService = params.some((p) => /CommandService\b|\bCommandBus\b/.test(p.typeText));
      if (hasDataSource) {
        failures.push({
          ruleId: "task-queue.controller.no-datasource",
          severity: "high",
          message: `The Task Controller injects DataSource directly: ${rel(file)} (use a CommandService or the idempotencyKey option instead)`,
          docRef: "docs/architecture/scheduling.md#taskcontroller--executing-commands-with-taskconsumer-methods-interface-layer"
        });
        score -= 4;
      }
      if (hasRepository) {
        failures.push({
          ruleId: "task-queue.controller.no-repository",
          severity: "high",
          message: `The Task Controller injects Repository<Entity> directly: ${rel(file)}`,
          docRef: "docs/architecture/scheduling.md#taskcontroller--executing-commands-with-taskconsumer-methods-interface-layer"
        });
        score -= 4;
      }
      const hasIdempotencyKeyOption = /idempotencyKey\s*:/.test(content);
      if (hasExecLog && hasIdempotencyKeyOption) {
        failures.push({
          ruleId: "task-queue.controller.double-ledger-check",
          severity: "medium",
          message: `The Task Controller both injects TaskExecutionLog and uses the idempotencyKey option: ${rel(file)} \u2014 this double-checks. Remove the option for a 3-step pattern, or remove the injection for a 2-step pattern`
        });
        score -= 2;
      }
      if (!hasCommandService) {
        failures.push({
          ruleId: "task-queue.controller.command-service-injection",
          severity: "medium",
          message: `The Task Controller has no CommandService/CommandBus injected: ${rel(file)}`
        });
        score -= 3;
      }
      for (const m of methods) {
        if (!m.decorators.some((d) => d.name === "TaskConsumer")) continue;
        if (/\bgenerateErrorResponse\s*\(/.test(m.body)) {
          failures.push({
            ruleId: "task-queue.controller.no-http-error-response",
            severity: "high",
            message: `Task Controller method ${m.methodName} calls generateErrorResponse: ${rel(file)} \u2014 exceptions must be propagated via throw and delegated to TaskQueueConsumer`
          });
          score -= 4;
        }
      }
    }
  }
  for (const [taskType, locations] of taskTypesSeen) {
    if (locations.length > 1) {
      failures.push({
        ruleId: "task-queue.task-type.unique",
        severity: "critical",
        message: `taskType '${taskType}' is registered redundantly in ${locations.length} places \u2014 ${locations.join(", ")}`,
        docRef: "docs/architecture/scheduling.md#taskconsumer-decorator"
      });
      score -= 6;
    }
  }
  const appModule = findAppModuleFile(files);
  if (appModule) {
    const appContent = fs12.readFileSync(appModule, "utf-8");
    if (anyCron && !/ScheduleModule\.forRoot\s*\(/.test(appContent)) {
      failures.push({
        ruleId: "task-queue.app-module.schedule-module",
        severity: "critical",
        message: `@Cron is used but AppModule has no ScheduleModule.forRoot() registration \u2014 the Cron method silently won't run`,
        docRef: "docs/architecture/scheduling.md#appmodule-configuration"
      });
      score -= 6;
    }
    if (anyTaskConsumer && !/TaskQueueModule\b/.test(appContent)) {
      failures.push({
        ruleId: "task-queue.app-module.task-queue-module",
        severity: "high",
        message: `@TaskConsumer is used but AppModule has no TaskQueueModule import`,
        docRef: "docs/architecture/scheduling.md#appmodule-configuration"
      });
      score -= 4;
    }
  }
  return { name: "task-queue", score: Math.max(score, 0), maxScore: 20, failures };
}

// evaluators/rules/scheduler.evaluator.ts
var fs13 = __toESM(require("node:fs"));
var path14 = __toESM(require("node:path"));
function isFrameworkInternal(filePath) {
  const normalized = filePath.replace(/\\/g, "/");
  return normalized.includes("/src/task-queue/") || normalized.includes("/src/outbox/");
}
function evaluateScheduler(root) {
  const failures = [];
  const srcDir = path14.join(root, "src");
  const files = walkTsFiles(srcDir);
  const rel = (f) => path14.relative(root, f);
  const anyCron = files.some((f) => /@Cron\s*\(|@Interval\s*\(/.test(fs13.readFileSync(f, "utf-8")));
  if (!anyCron) {
    return { name: "scheduler", score: 0, maxScore: 0, failures: [] };
  }
  let score = 15;
  for (const file of files) {
    const methods = listMethodDecorators(file);
    const cronMethods = methods.filter((m) => m.decorators.some((d) => d.name === "Cron" || d.name === "Interval"));
    if (cronMethods.length === 0) continue;
    const layer = classifyLayer(file);
    const frameworkInternal = isFrameworkInternal(file);
    if (!frameworkInternal && layer !== "infrastructure") {
      failures.push({
        ruleId: "scheduler.layer",
        severity: "high",
        message: `A Scheduler using @Cron/@Interval is located outside infrastructure/, in the ${layer} layer: ${rel(file)}`,
        docRef: "docs/architecture/scheduling.md#scheduler--cron--taskqueue"
      });
      score -= 4;
    }
    if (!frameworkInternal && !/-scheduler\.ts$/.test(path14.basename(file))) {
      failures.push({
        ruleId: "scheduler.file-suffix",
        severity: "medium",
        message: `An Infrastructure file with @Cron/@Interval must follow the *-scheduler.ts naming format: ${rel(file)}`
      });
      score -= 2;
    }
    for (const m of cronMethods) {
      const hasTry = /\btry\s*\{/.test(m.body);
      const hasCatch = /\bcatch\s*\(/.test(m.body);
      const usesRunSafely = /\brunSafely\s*\(/.test(m.body);
      if (!(hasTry && hasCatch) && !usesRunSafely) {
        failures.push({
          ruleId: "scheduler.cron.try-catch",
          severity: "medium",
          message: `Cron/Interval method ${m.methodName} has no try-catch (or runSafely helper): ${rel(file)} \u2014 @nestjs/schedule silently swallows exceptions`,
          docRef: "docs/architecture/scheduling.md#scheduler--cron--taskqueue"
        });
        score -= 2;
      }
    }
    if (!frameworkInternal) {
      const params = listConstructorParams(file);
      if (params.some((p) => /\bRepository<.+>/.test(p.typeText))) {
        failures.push({
          ruleId: "scheduler.no-repository-injection",
          severity: "high",
          message: `The Scheduler injects Repository<Entity> (suspected of containing business logic): ${rel(file)} \u2014 delegate it to TaskQueue`
        });
        score -= 3;
      }
      if (params.some((p) => /\bDataSource\b/.test(p.typeText))) {
        failures.push({
          ruleId: "scheduler.no-datasource-injection",
          severity: "high",
          message: `The Scheduler injects DataSource: ${rel(file)} \u2014 a Scheduler must only call TaskQueue.enqueue`
        });
        score -= 3;
      }
      if (params.some((p) => /CommandService\b/.test(p.typeText))) {
        failures.push({
          ruleId: "scheduler.no-command-service-injection",
          severity: "medium",
          message: `The Scheduler injects CommandService: ${rel(file)} \u2014 running business logic is the Task Controller's responsibility`
        });
        score -= 2;
      }
    }
  }
  return { name: "scheduler", score: Math.max(score, 0), maxScore: 15, failures };
}

// evaluators/rules/deprecated-api.evaluator.ts
var fs14 = __toESM(require("node:fs"));
var path15 = __toESM(require("node:path"));
var HTTP_METHOD_DECORATOR = /@(?:Get|Post|Put|Patch|Delete)\s*\(\s*['"`]([^'"`]*)['"`]?/g;
function extractMethods(content) {
  const results = [];
  const decoratorRegex = /@(Get|Post|Put|Patch|Delete)\s*\(([^)]*)\)/g;
  let m;
  while ((m = decoratorRegex.exec(content)) !== null) {
    const routePathMatch = m[2].match(/['"`]([^'"`]*)['"`]/);
    const routePath = routePathMatch?.[1] ?? "";
    const start = m.index;
    const openBrace = content.indexOf("{", m.index + m[0].length);
    if (openBrace === -1) continue;
    let depth = 1;
    let i = openBrace + 1;
    while (i < content.length && depth > 0) {
      if (content[i] === "{") depth += 1;
      else if (content[i] === "}") depth -= 1;
      i += 1;
    }
    const block = content.slice(start, i);
    const afterDecorators = content.slice(m.index, openBrace);
    const nameMatch = afterDecorators.match(/(?:public\s+|private\s+|protected\s+)?(?:async\s+)?(\w+)\s*\(/g);
    const lastNameMatch = nameMatch?.[nameMatch.length - 1]?.match(/(\w+)\s*\(/);
    const name = lastNameMatch?.[1] ?? "unknown";
    results.push({ name, routePath, block, start });
  }
  return results;
}
function evaluateDeprecatedApi(root) {
  const failures = [];
  const srcDir = path15.join(root, "src");
  const files = walkTsFiles(srcDir);
  const rel = (f) => path15.relative(root, f);
  void HTTP_METHOD_DECORATOR;
  const interfaceFilesWithHttp = files.filter(
    (f) => classifyLayer(f) === "interface" && /@(?:Get|Post|Put|Patch|Delete)\s*\(/.test(fs14.readFileSync(f, "utf-8"))
  );
  if (interfaceFilesWithHttp.length === 0) {
    return { name: "deprecated-api", score: 0, maxScore: 0, failures: [] };
  }
  let score = 10;
  for (const file of files) {
    if (classifyLayer(file) !== "interface") continue;
    const content = fs14.readFileSync(file, "utf-8");
    if (!/@(?:Get|Post|Put|Patch|Delete)\s*\(/.test(content)) continue;
    const methods = extractMethods(content);
    for (const m of methods) {
      const looksDeprecated = /deprecated|legacy/i.test(m.routePath) || /deprecated|legacy/i.test(m.name);
      const hasApiOperationDeprecated = /@ApiOperation\s*\(\s*\{[^}]*deprecated\s*:\s*true/.test(m.block);
      if (looksDeprecated && !hasApiOperationDeprecated) {
        failures.push({
          ruleId: "deprecated-api.missing-decorator",
          severity: "medium",
          message: `Suspected deprecated/legacy endpoint \u2014 missing @ApiOperation({ deprecated: true }): ${rel(file)} @ ${m.name}('${m.routePath}')`,
          docRef: "docs/conventions.md"
        });
        score -= 3;
      }
      if (hasApiOperationDeprecated) {
        const hasWarnLog = /logger\.warn\s*\(/.test(m.block);
        if (!hasWarnLog) {
          failures.push({
            ruleId: "deprecated-api.missing-warn-log",
            severity: "low",
            message: `Deprecated endpoint with no trace of a logger.warn call: ${rel(file)} @ ${m.name} \u2014 tracking lingering calls is recommended`
          });
          score -= 1;
        }
      }
    }
  }
  return { name: "deprecated-api", score: Math.max(score, 0), maxScore: 10, failures };
}

// evaluators/rules/module-di.ast.evaluator.ts
function evaluateModuleDI(root) {
  const files = walkTsFiles(`${root}/src`);
  const failures = [];
  let score = 25;
  for (const file of files) {
    if (!file.endsWith(".module.ts")) continue;
    if (!hasProviderArray(file)) {
      failures.push({ ruleId: "ast.module.providers-missing", severity: "high", message: file });
      score -= 5;
    }
  }
  return { name: "module-di-ast", score: Math.max(score, 0), maxScore: 25, failures };
}

// evaluators/rules/import-graph.evaluator.ts
function evaluateImportGraph(root) {
  const files = walkTsFiles(`${root}/src`);
  const failures = [];
  let score = 25;
  for (const file of files) {
    const fromLayer = classifyLayer(file);
    const imports = parseImports(file);
    for (const imp of imports) {
      if (imp.startsWith(".")) {
        const toLayer = classifyLayer(imp);
        if (fromLayer === "domain" && toLayer === "infrastructure") {
          failures.push({
            ruleId: "ast.layer.violation",
            severity: "high",
            message: `domain -> infrastructure dependency is forbidden: ${file}`
          });
          score -= 5;
        }
      }
    }
  }
  return {
    name: "import-graph",
    score: Math.max(score, 0),
    maxScore: 25,
    failures
  };
}

// evaluators/rules/domain-event-outbox.evaluator.ts
var fs15 = __toESM(require("node:fs"));
var path16 = __toESM(require("node:path"));
var DOC_REF2 = "docs/architecture/domain-events.md";
function stripComments(content) {
  return content.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
}
function collectDomainEventClassNames(domainFiles) {
  const names = /* @__PURE__ */ new Set();
  for (const f of domainFiles) {
    const content = fs15.readFileSync(f, "utf-8");
    const regex = /_events\.push\s*\(\s*new\s+(\w+)\s*\(/g;
    let m;
    while ((m = regex.exec(content)) !== null) {
      names.add(m[1]);
    }
  }
  return names;
}
function topDomainSegment(file, srcDir) {
  const relPath = path16.relative(srcDir, file).replace(/\\/g, "/");
  return relPath.split("/")[0];
}
function collectDomainEventClassNamesByDomain(domainFiles, srcDir) {
  const byDomain = /* @__PURE__ */ new Map();
  for (const f of domainFiles) {
    const content = fs15.readFileSync(f, "utf-8");
    const regex = /_events\.push\s*\(\s*new\s+(\w+)\s*\(/g;
    let m;
    while ((m = regex.exec(content)) !== null) {
      const domain = topDomainSegment(f, srcDir);
      if (!byDomain.has(domain)) byDomain.set(domain, /* @__PURE__ */ new Set());
      byDomain.get(domain).add(m[1]);
    }
  }
  return byDomain;
}
function evaluateDomainEventOutbox(root) {
  const failures = [];
  const srcDir = path16.join(root, "src");
  const files = walkTsFiles(srcDir);
  const rel = (f) => path16.relative(root, f);
  const domainFiles = files.filter((f) => classifyLayer(f) === "domain");
  const aggregatesWithEvents = domainFiles.filter((f) => {
    const c = fs15.readFileSync(f, "utf-8");
    return /\b(?:domainEvents|_events)\b/.test(c) && /\bpush\s*\(\s*new\s+\w+\s*\(/.test(c);
  });
  const hasHandleEvent = files.some((f) => /@HandleEvent\s*\(/.test(fs15.readFileSync(f, "utf-8")));
  const hasHandleIntegrationEvent = files.some((f) => /@HandleIntegrationEvent\s*\(/.test(fs15.readFileSync(f, "utf-8")));
  const hasEventBusPublish = files.some((f) => /\beventBus\s*\.\s*publish\s*\(/.test(fs15.readFileSync(f, "utf-8")));
  if (aggregatesWithEvents.length === 0 && !hasHandleEvent && !hasHandleIntegrationEvent && !hasEventBusPublish) {
    return { name: "domain-event-outbox", score: 0, maxScore: 0, failures: [] };
  }
  let score = 25;
  const outboxDir = path16.join(srcDir, "outbox");
  if (aggregatesWithEvents.length > 0 && !fs15.existsSync(outboxDir)) {
    failures.push({
      ruleId: "domain-event-outbox.module-missing",
      severity: "high",
      message: `Domain Events are published (${aggregatesWithEvents.length} Aggregates) but the shared src/outbox/ module is missing \u2014 the Outbox pattern must be set up`,
      docRef: DOC_REF2
    });
    score -= 6;
  }
  const infraFiles = files.filter((f) => classifyLayer(f) === "infrastructure");
  const repoImpls = infraFiles.filter((f) => /-repository-impl\.ts$/.test(path16.basename(f)));
  if (aggregatesWithEvents.length > 0) {
    if (repoImpls.length > 0) {
      const anyUsesOutbox = repoImpls.some((f) => {
        const c = fs15.readFileSync(f, "utf-8");
        return /\bOutboxWriter\b/.test(c) || /outbox[A-Za-z]*\.saveAll\s*\(/.test(c) || /\bdomainEvents\b[\s\S]*\boutbox\b/i.test(c);
      });
      if (!anyUsesOutbox) {
        failures.push({
          ruleId: "domain-event-outbox.repository-does-not-persist-events",
          severity: "high",
          message: `The Repository implementation does not use the OutboxWriter/outbox saveAll pattern \u2014 the domain events published by the Aggregate risk not being saved transactionally`,
          docRef: DOC_REF2
        });
        score -= 5;
      }
    } else {
      failures.push({
        ruleId: "domain-event-outbox.repository-impl-missing",
        severity: "medium",
        message: `Domain Events are published but no Repository implementation (-repository-impl.ts) was found`,
        docRef: DOC_REF2
      });
      score -= 3;
    }
    const clearEventsCalled = infraFiles.some((f) => /\bclearEvents\s*\(\s*\)/.test(fs15.readFileSync(f, "utf-8")));
    if (!clearEventsCalled) {
      failures.push({
        ruleId: "domain-event-outbox.clear-events-missing",
        severity: "low",
        message: `No trace of an Aggregate.clearEvents() call in the Repository implementation \u2014 recommend checking the convention that prevents duplicate event publication`,
        docRef: DOC_REF2
      });
      score -= 1;
    }
  }
  const eventClassNames = collectDomainEventClassNames(domainFiles);
  const applicationFiles = files.filter((f) => classifyLayer(f) === "application");
  for (const f of applicationFiles) {
    const content = fs15.readFileSync(f, "utf-8");
    for (const name of eventClassNames) {
      const pattern = new RegExp(`\\bnew\\s+${name}\\s*\\(`);
      if (pattern.test(content)) {
        failures.push({
          ruleId: "domain-event-outbox.command-service.event-construction",
          severity: "high",
          message: `The Application layer creates the domain event (${name}) directly: ${rel(f)} \u2014 events must only be created inside an Aggregate's domain methods`,
          docRef: DOC_REF2
        });
        score -= 4;
        break;
      }
    }
  }
  for (const f of applicationFiles) {
    const normalized = f.replace(/\\/g, "/");
    if (normalized.includes("/application/event/")) continue;
    const content = fs15.readFileSync(f, "utf-8");
    if (/\bOutboxWriter\b/.test(content)) {
      failures.push({
        ruleId: "domain-event-outbox.command-service.outbox-writer-injection",
        severity: "high",
        message: `The Application layer (outside application/event/) references OutboxWriter: ${rel(f)} \u2014 outbox may only be used by a Repository implementation or an application/event/ EventHandler`,
        docRef: DOC_REF2
      });
      score -= 4;
    }
  }
  for (const f of files) {
    const content = fs15.readFileSync(f, "utf-8");
    if (!/@HandleEvent\s*\(/.test(content)) continue;
    const normalized = f.replace(/\\/g, "/");
    const inEventDir = normalized.includes("/application/event/");
    const correctSuffix = /-handler\.ts$/.test(path16.basename(f));
    if (!inEventDir || !correctSuffix) {
      failures.push({
        ruleId: "domain-event-outbox.handler.layer",
        severity: "medium",
        message: `The file with @HandleEvent does not follow the application/event/<domain-event>-handler.ts path convention: ${rel(f)}`,
        docRef: DOC_REF2
      });
      score -= 2;
    }
  }
  for (const f of files) {
    const content = fs15.readFileSync(f, "utf-8");
    if (!/@HandleIntegrationEvent\s*\(/.test(content)) continue;
    const normalized = f.replace(/\\/g, "/");
    const inDir = normalized.includes("/interface/integration-event/");
    const correctSuffix = /-integration-event-controller\.ts$/.test(path16.basename(f));
    if (!inDir || !correctSuffix) {
      failures.push({
        ruleId: "domain-event-outbox.integration-event.controller.layer",
        severity: "medium",
        message: `The file with @HandleIntegrationEvent does not follow the interface/integration-event/<domain>-integration-event-controller.ts path convention: ${rel(f)}`,
        docRef: DOC_REF2
      });
      score -= 2;
    }
  }
  for (const f of files) {
    const content = fs15.readFileSync(f, "utf-8");
    if (/\beventBus\s*\.\s*publish\s*\(/.test(content)) {
      failures.push({
        ruleId: "domain-event-outbox.event-bus.direct-publish",
        severity: "high",
        message: `Direct call to EventBus.publish(): ${rel(f)} \u2014 even when using @nestjs/cqrs, it must go through the Outbox -> SQS path`,
        docRef: DOC_REF2
      });
      score -= 4;
    }
  }
  if (aggregatesWithEvents.length > 0) {
    const hasPoller = files.some((f) => /outbox-poller\.ts$/.test(path16.basename(f)));
    const hasConsumer = files.some((f) => /outbox-consumer\.ts$/.test(path16.basename(f)));
    if (!hasPoller) {
      failures.push({
        ruleId: "domain-event-outbox.poller-missing",
        severity: "high",
        message: "A Domain Event is published but outbox-poller.ts was not found \u2014 there is no path to publish events queued in the outbox table to the queue",
        docRef: DOC_REF2
      });
      score -= 5;
    }
    if (!hasConsumer) {
      failures.push({
        ruleId: "domain-event-outbox.consumer-missing",
        severity: "high",
        message: "A Domain Event is published but outbox-consumer.ts was not found \u2014 there is no path to receive from the queue and call an EventHandler",
        docRef: DOC_REF2
      });
      score -= 5;
    }
    if (eventClassNames.size > 0) {
      const registerCalls = files.map((f) => fs15.readFileSync(f, "utf-8")).join("\n");
      const eventsByDomain = collectDomainEventClassNamesByDomain(domainFiles, srcDir);
      for (const [domain, domainEvents] of eventsByDomain) {
        const missing = [...domainEvents].filter(
          (name) => !new RegExp(`\\bregister\\s*\\(\\s*['"\`]${name}['"\`]`).test(registerCalls)
        );
        if (missing.length > 0) {
          failures.push({
            ruleId: "domain-event-outbox.registry-coverage-incomplete",
            severity: "high",
            message: `The following Domain Events in the ${domain} domain are not registered via EventHandlerRegistry.register(...): ${missing.join(", ")} \u2014 even if queued in the Outbox, OutboxConsumer will find no handler to process them`,
            docRef: DOC_REF2
          });
          score -= 4;
        }
      }
    }
  }
  const commandHandlerFiles = files.filter(
    (f) => classifyLayer(f) === "application" && /-command-handler\.ts$/.test(path16.basename(f))
  );
  for (const f of commandHandlerFiles) {
    const content = stripComments(fs15.readFileSync(f, "utf-8"));
    const forbiddenSymbol = /\bOutboxRelay\b|\bOutboxPoller\b|\bOutboxConsumer\b/.exec(content);
    const forbiddenCall = /\.\s*(?:processPending|poll|drainOnce)\s*\(/.exec(content);
    if (forbiddenSymbol || forbiddenCall) {
      failures.push({
        ruleId: "domain-event-outbox.command-handler.forbidden-sync-drain",
        severity: "high",
        message: `${rel(f)} references OutboxRelay/OutboxPoller/OutboxConsumer directly or calls a drain \u2014 a Command Handler must return right after saving, and Outbox -> queue publish/receive is solely the responsibility of the independently-scheduled Poller/Consumer (synchronous draining is forbidden)`,
        docRef: DOC_REF2
      });
      score -= 6;
    }
  }
  return { name: "domain-event-outbox", score: Math.max(score, 0), maxScore: 25, failures };
}

// evaluators/rules/build.evaluator.ts
var import_node_child_process = require("node:child_process");
var fs16 = __toESM(require("node:fs"));
var path17 = __toESM(require("node:path"));
var MAX_ERROR_LINES = 25;
var FALLBACK_TYPESCRIPT_VERSION = "5.7.2";
function resolvePinnedTypescriptVersion(root) {
  try {
    const pkgPath = path17.join(root, "package.json");
    const pkg = JSON.parse(fs16.readFileSync(pkgPath, "utf-8"));
    const spec = pkg?.devDependencies?.typescript ?? pkg?.dependencies?.typescript;
    if (!spec) return FALLBACK_TYPESCRIPT_VERSION;
    const version = spec.replace(/^[~^]/, "").trim();
    return /^\d+\.\d+\.\d+$/.test(version) ? version : FALLBACK_TYPESCRIPT_VERSION;
  } catch {
    return FALLBACK_TYPESCRIPT_VERSION;
  }
}
function evaluateBuild(root) {
  const failures = [];
  const tsconfigPath = path17.join(root, "tsconfig.json");
  if (!fs16.existsSync(tsconfigPath)) {
    return { name: "build", score: 0, maxScore: 0, failures: [] };
  }
  const localTsc = path17.join(root, "node_modules", ".bin", "tsc");
  const cmd = fs16.existsSync(localTsc) ? localTsc : "npx";
  const tscPackageSpec = cmd === "npx" ? `typescript@${resolvePinnedTypescriptVersion(root)}` : "typescript";
  const args = cmd === "npx" ? ["--yes", "-p", tscPackageSpec, "tsc", "--noEmit"] : ["--noEmit"];
  const res = (0, import_node_child_process.spawnSync)(cmd, args, {
    cwd: root,
    encoding: "utf-8",
    env: { ...process.env, CI: "1" }
  });
  if (res.status === 0) {
    return { name: "build", score: 25, maxScore: 25, failures: [] };
  }
  const output = `${res.stdout ?? ""}${res.stderr ?? ""}`.trim();
  const lines = output.split("\n").slice(0, MAX_ERROR_LINES);
  for (const line of lines) {
    if (!line.trim()) continue;
    failures.push({
      ruleId: "build.tsc.error",
      severity: "critical",
      message: line
    });
  }
  return { name: "build", score: 0, maxScore: 25, failures };
}

// evaluators/rules/test-run.evaluator.ts
var import_node_child_process2 = require("node:child_process");
var fs17 = __toESM(require("node:fs"));
var path18 = __toESM(require("node:path"));
var MAX_ERROR_LINES2 = 30;
var OPT_IN_ENV = "HARNESS_ENABLE_TEST_RUN";
function evaluateTestRun(root) {
  const failures = [];
  const pkgPath = path18.join(root, "package.json");
  if (!fs17.existsSync(pkgPath)) {
    return { name: "test-run", score: 0, maxScore: 0, failures: [] };
  }
  if (process.env[OPT_IN_ENV] !== "1") {
    return {
      name: "test-run",
      score: 0,
      maxScore: 0,
      failures: [{
        ruleId: "test-run.skipped",
        severity: "low",
        message: `Skipped because it wasn't enabled with ${OPT_IN_ENV}=1 (opt-in for CI/local development)`
      }]
    };
  }
  try {
    const pkg = JSON.parse(fs17.readFileSync(pkgPath, "utf-8"));
    if (!pkg.scripts?.test) {
      return { name: "test-run", score: 0, maxScore: 0, failures: [] };
    }
  } catch {
    return { name: "test-run", score: 0, maxScore: 0, failures: [] };
  }
  const res = (0, import_node_child_process2.spawnSync)("npm", ["test", "--silent"], {
    cwd: root,
    encoding: "utf-8",
    env: { ...process.env, CI: "1" },
    timeout: 5 * 60 * 1e3
    // a 5-minute limit
  });
  if (res.status === 0) {
    return { name: "test-run", score: 20, maxScore: 20, failures: [] };
  }
  const output = `${res.stdout ?? ""}${res.stderr ?? ""}`.trim();
  const lines = output.split("\n").slice(-MAX_ERROR_LINES2);
  for (const line of lines) {
    if (!line.trim()) continue;
    failures.push({
      ruleId: "test-run.failure",
      severity: "high",
      message: line
    });
  }
  return { name: "test-run", score: 0, maxScore: 20, failures };
}

// evaluators/rules/secret-manager.evaluator.ts
var fs18 = __toESM(require("node:fs"));
var path19 = __toESM(require("node:path"));
var DOC_REF3 = "docs/architecture/secret-manager.md";
var SENSITIVE_KEY_PATTERN = /process\.env\.([A-Z_]*(?:PASSWORD|SECRET|APIKEY|API_KEY|TOKEN)[A-Z_]*)/g;
var GUARD_PATTERN = /\bNODE_ENV\b|\bSecretsManagerClient\b|\bSecretService\b|\bsecretService\b|\bgetSecret\b/;
function evaluateSecretManager(root) {
  const failures = [];
  const configDir = path19.join(root, "src", "config");
  if (!fs18.existsSync(configDir) || !fs18.statSync(configDir).isDirectory()) {
    return { name: "secret-manager", score: 0, maxScore: 0, failures: [] };
  }
  let score = 10;
  const rel = (f) => path19.relative(root, f);
  const configFiles = fs18.readdirSync(configDir).filter((name) => name.endsWith(".config.ts")).map((name) => path19.join(configDir, name));
  for (const file of configFiles) {
    const content = fs18.readFileSync(file, "utf-8");
    const sensitiveKeys = /* @__PURE__ */ new Set();
    SENSITIVE_KEY_PATTERN.lastIndex = 0;
    let m;
    while ((m = SENSITIVE_KEY_PATTERN.exec(content)) !== null) {
      sensitiveKeys.add(m[1]);
    }
    if (sensitiveKeys.size === 0) continue;
    if (GUARD_PATTERN.test(content)) continue;
    failures.push({
      ruleId: "secret-manager.config.sensitive-env-without-guard",
      severity: "high",
      message: `${rel(file)} reads sensitive keys (${[...sensitiveKeys].join(", ")}) only from process.env \u2014 a NODE_ENV branch or SecretService/SecretsManagerClient is required`,
      docRef: DOC_REF3
    });
    score -= 4;
  }
  return { name: "secret-manager", score: Math.max(score, 0), maxScore: 10, failures };
}

// evaluators/rules/config-validation.evaluator.ts
var fs19 = __toESM(require("node:fs"));
var path20 = __toESM(require("node:path"));
var DOC_REF4 = "docs/architecture/config.md";
var PROCESS_ENV_PATTERN = /\bprocess\.env\b/g;
function walkFiles(root, predicate) {
  const out = [];
  if (!fs19.existsSync(root)) return out;
  for (const entry of fs19.readdirSync(root)) {
    if (entry === "node_modules" || entry === "dist" || entry === "coverage" || entry === ".git") continue;
    const fullPath = path20.join(root, entry);
    const stat = fs19.statSync(fullPath);
    if (stat.isDirectory()) {
      out.push(...walkFiles(fullPath, predicate));
      continue;
    }
    if (predicate(fullPath)) out.push(fullPath);
  }
  return out;
}
function isConfigFactoryFile(root, file) {
  const rel = path20.relative(root, file).replace(/\\/g, "/");
  return /^src\/config\/[^/]+\.config\.ts$/.test(rel);
}
function isTypeScriptSource(file) {
  return file.endsWith(".ts") && !file.endsWith(".d.ts") && !file.endsWith(".spec.ts");
}
function hasConfigModuleUsage(files) {
  return files.some((file) => fs19.readFileSync(file, "utf-8").includes("ConfigModule"));
}
function hasForRootWithoutValidation(content) {
  const forRootIndex = content.indexOf("ConfigModule.forRoot");
  if (forRootIndex < 0) return false;
  const after = content.slice(forRootIndex, forRootIndex + 1200);
  return !/\bvalidationSchema\b|\bvalidate\b/.test(after);
}
function evaluateConfigValidation(root) {
  const srcRoot = path20.join(root, "src");
  const configDir = path20.join(srcRoot, "config");
  const tsFiles = walkFiles(srcRoot, isTypeScriptSource);
  if (!fs19.existsSync(configDir) && !hasConfigModuleUsage(tsFiles)) {
    return { name: "config-validation", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 20;
  const rel = (file) => path20.relative(root, file);
  const configFiles = tsFiles.filter((file) => path20.relative(root, file).replace(/\\/g, "/").startsWith("src/config/"));
  for (const file of configFiles) {
    if (!file.endsWith(".config.ts") && !file.endsWith("/index.ts")) {
      failures.push({
        ruleId: "config.file-naming",
        severity: "low",
        message: `${rel(file)} does not follow the config factory file naming convention (*.config.ts)`,
        docRef: DOC_REF4
      });
      score -= 1;
    }
  }
  for (const file of tsFiles) {
    const content = fs19.readFileSync(file, "utf-8");
    if (hasForRootWithoutValidation(content)) {
      failures.push({
        ruleId: "config.validation-required",
        severity: "high",
        message: `${rel(file)}'s ConfigModule.forRoot() has no validationSchema or validate option`,
        docRef: DOC_REF4
      });
      score -= 4;
    }
    if (!isConfigFactoryFile(root, file) && PROCESS_ENV_PATTERN.test(content)) {
      failures.push({
        ruleId: "config.process-env-direct-access",
        severity: "medium",
        message: `${rel(file)} references process.env directly \u2014 it must be encapsulated in src/config/*.config.ts`,
        docRef: DOC_REF4
      });
      score -= 2;
    }
    PROCESS_ENV_PATTERN.lastIndex = 0;
  }
  return { name: "config-validation", score: Math.max(score, 0), maxScore: 20, failures };
}

// evaluators/rules/logging.evaluator.ts
var fs20 = __toESM(require("node:fs"));
var path21 = __toESM(require("node:path"));
var DOC_REF5 = "docs/architecture/observability.md";
var DOMAIN_LOGGING_DOC_REF = `${DOC_REF5}#per-layer-logging-criteria`;
var CONSOLE_PATTERN = /\bconsole\.(log|warn|error|debug|info)\s*\(/g;
var EMPTY_CATCH_PATTERN = /catch\s*\([^)]*\)\s*\{\s*\}/g;
var CATCH_BLOCK_PATTERN = /catch\s*\([^)]*\)\s*\{([\s\S]*?)\}/g;
var HANDLED_ERROR_PATTERN = /\b(logger|this\.logger|Logger)\.(error|warn|log|debug)\s*\(|\bthrow\b/;
var NESTJS_LOGGER_NAMED_IMPORT_PATTERN = /import\s*\{[^}]*\bLogger\b[^}]*\}\s*from\s*['"]@nestjs\/common['"]/;
var WINSTON_IMPORT_PATTERN = /from\s*['"]winston['"]/;
var DOMAIN_LOGGER_USAGE_PATTERN = /\bnew\s+Logger\s*\(|\blogger\.(log|error|warn|debug|verbose)\s*\(/;
function walkTsFiles2(root) {
  const out = [];
  if (!fs20.existsSync(root)) return out;
  for (const entry of fs20.readdirSync(root)) {
    if (entry === "node_modules" || entry === "dist" || entry === "coverage" || entry === ".git") continue;
    const fullPath = path21.join(root, entry);
    const stat = fs20.statSync(fullPath);
    if (stat.isDirectory()) {
      out.push(...walkTsFiles2(fullPath));
      continue;
    }
    if (fullPath.endsWith(".ts") && !fullPath.endsWith(".d.ts") && !fullPath.endsWith(".spec.ts")) {
      out.push(fullPath);
    }
  }
  return out;
}
function lineOf(source, index) {
  return source.slice(0, index).split("\n").length;
}
function evaluateLogging(root) {
  const srcRoot = path21.join(root, "src");
  const files = walkTsFiles2(srcRoot);
  if (files.length === 0) {
    return { name: "logging", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (file) => path21.relative(root, file);
  for (const file of files) {
    const content = fs20.readFileSync(file, "utf-8");
    CONSOLE_PATTERN.lastIndex = 0;
    let consoleMatch;
    while ((consoleMatch = CONSOLE_PATTERN.exec(content)) !== null) {
      failures.push({
        ruleId: "logging.no-console",
        severity: "medium",
        message: `${rel(file)}:${lineOf(content, consoleMatch.index)} uses console.${consoleMatch[1]} directly \u2014 must use Logger instead`,
        docRef: DOC_REF5
      });
      score -= 2;
    }
    EMPTY_CATCH_PATTERN.lastIndex = 0;
    let emptyCatchMatch;
    while ((emptyCatchMatch = EMPTY_CATCH_PATTERN.exec(content)) !== null) {
      failures.push({
        ruleId: "logging.no-empty-catch",
        severity: "high",
        message: `${rel(file)}:${lineOf(content, emptyCatchMatch.index)}'s catch block is empty \u2014 must log the error or rethrow it`,
        docRef: DOC_REF5
      });
      score -= 4;
    }
    CATCH_BLOCK_PATTERN.lastIndex = 0;
    let catchMatch;
    while ((catchMatch = CATCH_BLOCK_PATTERN.exec(content)) !== null) {
      const block = catchMatch[1];
      if (block.trim().length === 0) continue;
      if (HANDLED_ERROR_PATTERN.test(block)) continue;
      failures.push({
        ruleId: "logging.no-swallowed-error",
        severity: "high",
        message: `${rel(file)}:${lineOf(content, catchMatch.index)}'s catch block neither logs the error nor rethrows it`,
        docRef: DOC_REF5
      });
      score -= 4;
    }
    if (file.replace(/\\/g, "/").includes("/domain/")) {
      const usesNestjsLogger = NESTJS_LOGGER_NAMED_IMPORT_PATTERN.test(content) || DOMAIN_LOGGER_USAGE_PATTERN.test(content);
      const usesWinston = WINSTON_IMPORT_PATTERN.test(content);
      if (usesNestjsLogger || usesWinston) {
        failures.push({
          ruleId: "logging.no-logging-in-domain",
          severity: "high",
          message: `${rel(file)} \u2014 logging is forbidden in the domain layer (${usesWinston ? "winston" : "@nestjs/common Logger"}). The result of domain logic must be logged in the Application layer`,
          docRef: DOMAIN_LOGGING_DOC_REF
        });
        score -= 4;
      }
    }
  }
  return { name: "logging", score: Math.max(score, 0), maxScore: 15, failures };
}

// evaluators/rules/auth.evaluator.ts
var fs21 = __toESM(require("node:fs"));
var path22 = __toESM(require("node:path"));
var DOC_REF6 = "docs/architecture/authentication.md";
var METHOD_PATTERN = /@(Get|Post|Put|Patch|Delete)\s*\([^)]*\)[\s\S]*?(?:async\s+)?([A-Za-z0-9_]+)\s*\(/g;
var PROTECTED_OR_PUBLIC_PATTERN = /@UseGuards\s*\(|@Authenticated\s*\(|@Public\s*\(|@SkipAuth\s*\(|@AllowAnonymous\s*\(/;
var AUTH_FILE_PATTERN = /(auth|jwt|guard|strategy)/i;
function walkFiles2(root) {
  const out = [];
  if (!fs21.existsSync(root)) return out;
  for (const entry of fs21.readdirSync(root)) {
    if (entry === "node_modules" || entry === "dist" || entry === "coverage" || entry === ".git") continue;
    const fullPath = path22.join(root, entry);
    const stat = fs21.statSync(fullPath);
    if (stat.isDirectory()) {
      out.push(...walkFiles2(fullPath));
      continue;
    }
    if (fullPath.endsWith(".ts") && !fullPath.endsWith(".d.ts")) out.push(fullPath);
  }
  return out;
}
function lineOf2(source, index) {
  return source.slice(0, index).split("\n").length;
}
function hasAuthInfrastructure(files) {
  return files.some((file) => AUTH_FILE_PATTERN.test(file) || /UseGuards|JwtStrategy|PassportStrategy|AuthGuard/.test(fs21.readFileSync(file, "utf-8")));
}
function evaluateAuth(root) {
  const srcRoot = path22.join(root, "src");
  const files = walkFiles2(srcRoot);
  const controllerFiles = files.filter((file) => file.endsWith("controller.ts"));
  if (controllerFiles.length === 0) {
    return { name: "auth", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 20;
  const rel = (file) => path22.relative(root, file);
  if (!hasAuthInfrastructure(files)) {
    failures.push({
      ruleId: "auth.jwt-strategy-required",
      severity: "medium",
      message: "Controllers exist but no AuthGuard/JwtStrategy/guard-related authentication configuration was found",
      docRef: DOC_REF6
    });
    score -= 2;
  }
  for (const file of controllerFiles) {
    const content = fs21.readFileSync(file, "utf-8");
    const classHasIntent = PROTECTED_OR_PUBLIC_PATTERN.test(content.slice(0, content.indexOf("export class") > -1 ? content.indexOf("export class") : content.length));
    METHOD_PATTERN.lastIndex = 0;
    let methodMatch;
    let methodCount = 0;
    while ((methodMatch = METHOD_PATTERN.exec(content)) !== null) {
      methodCount += 1;
      const methodStart = Math.max(0, methodMatch.index - 300);
      const methodDecorators = content.slice(methodStart, methodMatch.index);
      if (classHasIntent || PROTECTED_OR_PUBLIC_PATTERN.test(methodDecorators)) continue;
      failures.push({
        ruleId: "auth.route-intent-required",
        severity: "medium",
        message: `${rel(file)}:${lineOf2(content, methodMatch.index)} the ${methodMatch[2]} route has no @UseGuards, @Authenticated, or @Public intent marker`,
        docRef: DOC_REF6
      });
      score -= 2;
    }
    if (methodCount > 0 && !PROTECTED_OR_PUBLIC_PATTERN.test(content)) {
      failures.push({
        ruleId: "auth.controller-intent-required",
        severity: "medium",
        message: `${rel(file)} has no protection/public intent marker at all (@UseGuards, @Authenticated, or @Public)`,
        docRef: DOC_REF6
      });
      score -= 2;
    }
  }
  return { name: "auth", score: Math.max(score, 0), maxScore: 20, failures };
}

// evaluators/rules/bootstrap-healthcheck.evaluator.ts
var fs22 = __toESM(require("node:fs"));
var path23 = __toESM(require("node:path"));
var BOOTSTRAP_DOC_REF = "docs/architecture/bootstrap.md";
var HEALTH_DOC_REF = "docs/architecture/graceful-shutdown.md";
function readBootstrapSource(root, main2) {
  const setupImport = /from\s+'(?:@\/|\.{1,2}\/)([\w./-]*app-setup)'/.exec(main2);
  if (!setupImport) return main2;
  const setupPath = path23.join(root, "src", `${setupImport[1]}.ts`);
  if (!fs22.existsSync(setupPath)) return main2;
  return main2 + "\n" + fs22.readFileSync(setupPath, "utf-8");
}
function evaluateBootstrapHealthcheck(root) {
  const mainPath = path23.join(root, "src", "main.ts");
  if (!fs22.existsSync(mainPath)) return { name: "bootstrap-healthcheck", score: 0, maxScore: 0, failures: [] };
  const failures = [];
  let score = 20;
  const main2 = readBootstrapSource(root, fs22.readFileSync(mainPath, "utf-8"));
  if (!main2.includes("enableShutdownHooks")) {
    failures.push({ ruleId: "bootstrap.shutdown-hooks", severity: "high", message: "enableShutdownHooks is missing", docRef: HEALTH_DOC_REF });
    score -= 4;
  }
  if (!main2.includes("ValidationPipe")) {
    failures.push({ ruleId: "bootstrap.validation-pipe", severity: "high", message: "ValidationPipe is missing", docRef: BOOTSTRAP_DOC_REF });
    score -= 4;
  }
  return { name: "bootstrap-healthcheck", score: Math.max(score, 0), maxScore: 20, failures };
}

// evaluators/rules/e2e-quality.evaluator.ts
var fs23 = __toESM(require("node:fs"));
var path24 = __toESM(require("node:path"));
var DOC2 = "docs/architecture/testing.md";
function evaluateE2eQuality(root) {
  const failures = [];
  const testDir = path24.join(root, "test");
  if (!fs23.existsSync(testDir)) {
    return { name: "e2e-quality", score: 0, maxScore: 0, failures: [] };
  }
  const e2eFiles = fs23.readdirSync(testDir).filter((f) => f.endsWith(".e2e-spec.ts"));
  if (e2eFiles.length === 0) {
    return { name: "e2e-quality", score: 0, maxScore: 0, failures: [] };
  }
  let score = 20;
  for (const file of e2eFiles) {
    const content = fs23.readFileSync(path24.join(testDir, file), "utf-8");
    if (content.includes("jest.mock(")) {
      failures.push({
        ruleId: "e2e.jest-mock-in-e2e",
        severity: "high",
        message: `jest.mock() must not be used in E2E tests: test/${file} \u2014 replace external HTTP with nock and the DB with testcontainers`,
        docRef: `${DOC2}#mocking-external-http-nock`
      });
      score -= penaltyFor("high");
    }
  }
  const hasTooling = (() => {
    const pkgPath = path24.join(root, "package.json");
    if (fs23.existsSync(pkgPath)) {
      try {
        const pkg = JSON.parse(fs23.readFileSync(pkgPath, "utf-8"));
        const deps = { ...pkg.dependencies, ...pkg.devDependencies };
        if ("nock" in deps || "testcontainers" in deps || "@testcontainers/postgresql" in deps) {
          return true;
        }
      } catch {
      }
    }
    for (const file of e2eFiles) {
      const content = fs23.readFileSync(path24.join(testDir, file), "utf-8");
      if (content.includes("from 'nock'") || content.includes("require('nock')") || content.includes("from 'testcontainers'") || content.includes("from '@testcontainers/") || content.includes("require('testcontainers')")) {
        return true;
      }
    }
    return false;
  })();
  if (!hasTooling) {
    failures.push({
      ruleId: "e2e.no-nock-or-testcontainers",
      severity: "medium",
      message: "The nock or testcontainers package is missing from E2E tests. Use nock for external HTTP and testcontainers for the DB.",
      docRef: `${DOC2}#sqlite-vs-testcontainers-selection-criteria`
    });
    score -= penaltyFor("medium");
  }
  return {
    name: "e2e-quality",
    score: Math.max(score, 0),
    maxScore: 20,
    failures
  };
}

// evaluators/rules/dockerfile.evaluator.ts
var fs24 = __toESM(require("node:fs"));
var path25 = __toESM(require("node:path"));
var DOC3 = "docs/architecture/container.md";
function evaluateDockerfile(root) {
  const dockerfilePath = path25.join(root, "Dockerfile");
  if (!fs24.existsSync(dockerfilePath)) {
    return { name: "dockerfile", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const content = fs24.readFileSync(dockerfilePath, "utf-8");
  if (!/\bAS\s+build\b/i.test(content)) {
    failures.push({
      ruleId: "dockerfile.multistage-required",
      severity: "critical",
      message: "The Dockerfile has no multi-stage build (AS build). A build -> production two-stage structure is required.",
      docRef: DOC3
    });
    score -= penaltyFor("critical");
  }
  if (/^\s*CMD\s+\[?"npm/m.test(content) || /^\s*CMD\s+\[?"yarn/m.test(content)) {
    failures.push({
      ruleId: "dockerfile.cmd-node-direct",
      severity: "high",
      message: "CMD must run node dist/main.js directly instead of an npm/yarn wrapper. npm does not forward SIGTERM to its child process.",
      docRef: DOC3
    });
    score -= penaltyFor("high");
  }
  if (!/npm\s+ci\s+--omit=dev|npm\s+install\s+--production|npm\s+ci\s+--only=production/m.test(content)) {
    failures.push({
      ruleId: "dockerfile.prod-deps-only",
      severity: "medium",
      message: "The Dockerfile production stage must exclude devDependencies with npm ci --omit=dev.",
      docRef: DOC3
    });
    score -= penaltyFor("medium");
  }
  if (!/^\s*USER\s+\S+/m.test(content)) {
    failures.push({
      ruleId: "dockerfile.non-root-user-missing",
      severity: "high",
      message: 'The Dockerfile has no USER directive \u2014 the container runs as root. It must switch to a non-root user (node:alpine provides a ready-to-use "node" user via USER node).',
      docRef: DOC3
    });
    score -= penaltyFor("high");
  }
  if (!fs24.existsSync(path25.join(root, ".dockerignore"))) {
    failures.push({
      ruleId: "dockerfile.dockerignore-missing",
      severity: "medium",
      message: "The .dockerignore file is missing. It must exclude node_modules, dist, .env*, etc.",
      docRef: DOC3
    });
    score -= penaltyFor("medium");
  }
  if (!/^\s*HEALTHCHECK\b/m.test(content)) {
    failures.push({
      ruleId: "dockerfile.healthcheck-missing",
      severity: "medium",
      message: "The HEALTHCHECK directive is missing. It is needed to check container health directly in a standalone docker run environment (it may be omitted if an orchestrator already handles liveness/readiness probes).",
      docRef: `${DOC3}#principles`
    });
    score -= penaltyFor("medium");
  }
  return {
    name: "dockerfile",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/local-dev.evaluator.ts
var fs25 = __toESM(require("node:fs"));
var path26 = __toESM(require("node:path"));
var DOC4 = "docs/architecture/local-dev.md";
function evaluateLocalDev(root) {
  const composePath = path26.join(root, "docker-compose.yml");
  const composeYmlPath = path26.join(root, "docker-compose.yaml");
  const composefile = fs25.existsSync(composePath) ? composePath : fs25.existsSync(composeYmlPath) ? composeYmlPath : null;
  if (!composefile) {
    return { name: "local-dev", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const content = fs25.readFileSync(composefile, "utf-8");
  if (!/postgres/i.test(content)) {
    failures.push({
      ruleId: "local-dev.postgres-service-missing",
      severity: "high",
      message: "docker-compose.yml has no postgres service.",
      docRef: DOC4
    });
    score -= penaltyFor("high");
  }
  if (!/healthcheck/i.test(content)) {
    failures.push({
      ruleId: "local-dev.healthcheck-missing",
      severity: "medium",
      message: "The docker-compose.yml service has no healthcheck. It is needed for depends_on condition: service_healthy.",
      docRef: DOC4
    });
    score -= penaltyFor("medium");
  }
  const hasEnvFile = fs25.existsSync(path26.join(root, ".env.development")) || fs25.existsSync(path26.join(root, ".env.example")) || fs25.existsSync(path26.join(root, ".env"));
  if (!hasEnvFile) {
    failures.push({
      ruleId: "local-dev.env-file-missing",
      severity: "low",
      message: "The .env.development or .env.example file is missing.",
      docRef: DOC4
    });
    score -= penaltyFor("low");
  }
  return {
    name: "local-dev",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/rate-limiting.evaluator.ts
var fs26 = __toESM(require("node:fs"));
var path27 = __toESM(require("node:path"));
var DOC5 = "docs/architecture/rate-limiting.md";
function walkTsFiles3(root) {
  const out = [];
  if (!fs26.existsSync(root)) return out;
  for (const entry of fs26.readdirSync(root)) {
    if (["node_modules", "dist", "coverage", ".git"].includes(entry)) continue;
    const full = path27.join(root, entry);
    if (fs26.statSync(full).isDirectory()) {
      out.push(...walkTsFiles3(full));
      continue;
    }
    if (full.endsWith(".ts") && !full.endsWith(".d.ts")) out.push(full);
  }
  return out;
}
function hasThrottlerUsage(files) {
  return files.some((f) => {
    const c = fs26.readFileSync(f, "utf-8");
    return c.includes("ThrottlerModule") || c.includes("@nestjs/throttler");
  });
}
function hasThrottlerInPackage(root) {
  const pkgPath = path27.join(root, "package.json");
  if (!fs26.existsSync(pkgPath)) return false;
  try {
    const pkg = JSON.parse(fs26.readFileSync(pkgPath, "utf-8"));
    return "@nestjs/throttler" in { ...pkg.dependencies, ...pkg.devDependencies };
  } catch {
    return false;
  }
}
function evaluateRateLimiting(root) {
  const srcRoot = path27.join(root, "src");
  const files = walkTsFiles3(srcRoot);
  if (!hasThrottlerInPackage(root) && !hasThrottlerUsage(files)) {
    return { name: "rate-limiting", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 10;
  const allContent = files.map((f) => fs26.readFileSync(f, "utf-8")).join("\n");
  if (!/ThrottlerModule\.(forRoot|forRootAsync)\s*\(/.test(allContent)) {
    failures.push({
      ruleId: "rate-limiting.throttler-module-missing",
      severity: "high",
      message: "No ThrottlerModule.forRoot() or forRootAsync() configuration was found. It must be registered in AppModule.",
      docRef: DOC5
    });
    score -= penaltyFor("high");
  }
  const moduleFiles = files.filter((f) => /@Module\s*\(/.test(fs26.readFileSync(f, "utf-8")));
  const wiredGlobally = moduleFiles.some((f) => {
    const decoratorText = findClassDecorator(f, "Module");
    return decoratorText !== null && /APP_GUARD/.test(decoratorText) && /ThrottlerGuard/.test(decoratorText);
  });
  const wiredViaUseGuards = files.some((f) => {
    const content = fs26.readFileSync(f, "utf-8");
    return /@Controller\s*\(/.test(content) && /@UseGuards\([^)]*ThrottlerGuard[^)]*\)/.test(content);
  });
  if (!wiredGlobally && !wiredViaUseGuards) {
    failures.push({
      ruleId: "rate-limiting.app-guard-missing",
      severity: "medium",
      message: "Neither a global guard registration ({ provide: APP_GUARD, useClass: ThrottlerGuard } in @Module providers) nor a controller-level @UseGuards(ThrottlerGuard) was found \u2014 ThrottlerModule may be configured but never actually applied (dead code).",
      docRef: DOC5
    });
    score -= penaltyFor("medium");
  }
  return {
    name: "rate-limiting",
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  };
}

// evaluators/rules/pagination.evaluator.ts
var fs27 = __toESM(require("node:fs"));
var path28 = __toESM(require("node:path"));
var DOC6 = "docs/architecture/api-response.md";
function walkTsFiles4(root) {
  const out = [];
  if (!fs27.existsSync(root)) return out;
  for (const entry of fs27.readdirSync(root)) {
    if (["node_modules", "dist", "coverage", ".git"].includes(entry)) continue;
    const full = path28.join(root, entry);
    if (fs27.statSync(full).isDirectory()) {
      out.push(...walkTsFiles4(full));
      continue;
    }
    if (full.endsWith(".ts") && !full.endsWith(".d.ts") && !full.endsWith(".spec.ts")) out.push(full);
  }
  return out;
}
function resolveImportPath3(moduleSpecifier, fromFile, srcRoot) {
  const withExt = moduleSpecifier.endsWith(".ts") ? moduleSpecifier : `${moduleSpecifier}.ts`;
  if (moduleSpecifier.startsWith("@/")) return path28.join(srcRoot, withExt.slice(2));
  return path28.resolve(path28.dirname(fromFile), withExt);
}
function findExtendsBaseName2(content) {
  const match = content.match(/class\s+\w+\s+extends\s+(\w+)/);
  return match ? match[1] : null;
}
function findImportModuleFor2(content, name) {
  const importRegex = /import\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]/g;
  let m;
  while (m = importRegex.exec(content)) {
    const names = m[1].split(",").map((s) => s.trim());
    if (names.includes(name)) return m[2];
  }
  return null;
}
function collectContentWithBases2(filePath, srcRoot, depth = 0) {
  const content = fs27.readFileSync(filePath, "utf-8");
  if (depth >= 3) return content;
  const baseName = findExtendsBaseName2(content);
  if (!baseName) return content;
  const modulePath = findImportModuleFor2(content, baseName);
  if (!modulePath) return content;
  const resolved = resolveImportPath3(modulePath, filePath, srcRoot);
  if (!fs27.existsSync(resolved)) return content;
  return `${content}
${collectContentWithBases2(resolved, srcRoot, depth + 1)}`;
}
function isPaginationDto(content) {
  return /\bpage\b/.test(content) && /\btake\b/.test(content);
}
function evaluatePagination(root) {
  const srcRoot = path28.join(root, "src");
  const files = walkTsFiles4(srcRoot);
  const contentOf = /* @__PURE__ */ new Map();
  const read = (f) => {
    let c = contentOf.get(f);
    if (c === void 0) {
      c = collectContentWithBases2(f, srcRoot);
      contentOf.set(f, c);
    }
    return c;
  };
  const paginationDtoFiles = files.filter((f) => f.includes("dto") && isPaginationDto(read(f)));
  if (paginationDtoFiles.length === 0) {
    return { name: "pagination", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path28.relative(root, f);
  for (const file of paginationDtoFiles) {
    const content = read(file);
    const hasPageType = /@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)[\s\S]{0,100}page\b|page\b[\s\S]{0,200}@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)/.test(content);
    const hasPageInt = /@IsInt\s*\(\s*\)[\s\S]{0,100}page\b|page\b[\s\S]{0,200}@IsInt\s*\(\s*\)/.test(content);
    if (!hasPageType || !hasPageInt) {
      failures.push({
        ruleId: "pagination.page-decorator-missing",
        severity: "medium",
        message: `${rel(file)}'s page field needs @Type(() => Number) and @IsInt() decorators.`,
        docRef: DOC6
      });
      score -= penaltyFor("medium");
    }
    const hasTakeType = /@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)[\s\S]{0,100}take\b|take\b[\s\S]{0,200}@Type\s*\(\s*\(\s*\)\s*=>\s*Number\s*\)/.test(content);
    const hasTakeInt = /@IsInt\s*\(\s*\)[\s\S]{0,100}take\b|take\b[\s\S]{0,200}@IsInt\s*\(\s*\)/.test(content);
    if (!hasTakeType || !hasTakeInt) {
      failures.push({
        ruleId: "pagination.take-decorator-missing",
        severity: "medium",
        message: `${rel(file)}'s take field needs @Type(() => Number) and @IsInt() decorators.`,
        docRef: DOC6
      });
      score -= penaltyFor("medium");
    }
  }
  const repoFiles = files.filter((f) => f.endsWith("-repository.ts") || f.endsWith("-repository-impl.ts"));
  for (const file of repoFiles) {
    const content = fs27.readFileSync(file, "utf-8");
    const promiseObjectPattern = /Promise<\{([^}]*)\}>/g;
    let match;
    let flagged = false;
    while (!flagged && (match = promiseObjectPattern.exec(content)) !== null) {
      const body = match[1];
      if (/\b(data|items|result)\s*:/.test(body) && /\bcount\b/.test(body)) flagged = true;
    }
    if (flagged) {
      failures.push({
        ruleId: "pagination.generic-response-key",
        severity: "medium",
        message: `${rel(file)}'s pagination response must not use generic keys like data/items/result. Use a domain-specific plural instead (e.g. orders, users).`,
        docRef: DOC6
      });
      score -= penaltyFor("medium");
    }
  }
  return {
    name: "pagination",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/database-queries.evaluator.ts
var fs28 = __toESM(require("node:fs"));
var path29 = __toESM(require("node:path"));
var DOC7 = "docs/architecture/persistence.md";
function walkTsFiles5(root) {
  const out = [];
  if (!fs28.existsSync(root)) return out;
  for (const entry of fs28.readdirSync(root)) {
    if (["node_modules", "dist", "coverage", ".git"].includes(entry)) continue;
    const full = path29.join(root, entry);
    if (fs28.statSync(full).isDirectory()) {
      out.push(...walkTsFiles5(full));
      continue;
    }
    if (full.endsWith(".ts") && !full.endsWith(".d.ts") && !full.endsWith(".spec.ts")) out.push(full);
  }
  return out;
}
function evaluateDatabaseQueries(root) {
  const srcRoot = path29.join(root, "src");
  const files = walkTsFiles5(srcRoot);
  const entityFiles = files.filter((f) => f.endsWith(".entity.ts"));
  if (entityFiles.length === 0) {
    return { name: "database-queries", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 20;
  const rel = (f) => path29.relative(root, f);
  for (const file of entityFiles) {
    const content = fs28.readFileSync(file, "utf-8");
    if (/@PrimaryGeneratedColumn\s*\(/.test(content)) {
      failures.push({
        ruleId: "database-queries.primary-generated-column",
        severity: "high",
        message: `${rel(file)} must not use @PrimaryGeneratedColumn() \u2014 use @PrimaryColumn({ type: 'char', length: 32 }) and generate the ID with generateId().`,
        docRef: DOC7
      });
      score -= penaltyFor("high");
    }
  }
  const infraFiles = files.filter((f) => f.includes("/infrastructure/"));
  for (const file of infraFiles) {
    const content = fs28.readFileSync(file, "utf-8");
    if (/\b(manager|repository|this\.\w+)\s*\.\s*delete\s*\(/.test(content) && !/softDelete/.test(content)) {
      failures.push({
        ruleId: "database-queries.hard-delete-forbidden",
        severity: "high",
        message: `${rel(file)} must not call .delete() directly \u2014 use softDelete() for a logical delete.`,
        docRef: DOC7
      });
      score -= penaltyFor("high");
    }
  }
  for (const file of entityFiles) {
    const content = fs28.readFileSync(file, "utf-8");
    if (/export\s+abstract\s+class\s+\w*BaseEntity/.test(content)) continue;
    const auditColumnCount = [
      /@CreateDateColumn/,
      /@UpdateDateColumn/,
      /@DeleteDateColumn/
    ].filter((re) => re.test(content)).length;
    const extendsBaseEntity = /extends\s+\w*BaseEntity/.test(content);
    if (!extendsBaseEntity && (auditColumnCount === 3 || auditColumnCount === 0)) {
      failures.push({
        ruleId: "database-queries.base-entity-missing",
        severity: "medium",
        message: `${rel(file)} does not extend BaseEntity. Reuse the common createdAt/updatedAt/deletedAt columns by extending BaseEntity (do not redeclare @CreateDateColumn/@UpdateDateColumn/@DeleteDateColumn inline).`,
        docRef: DOC7
      });
      score -= penaltyFor("medium");
    }
  }
  const hasTxManager = fs28.existsSync(path29.join(srcRoot, "database", "transaction-manager.ts")) || files.some((f) => f.endsWith("transaction-manager.ts"));
  if (!hasTxManager) {
    failures.push({
      ruleId: "database-queries.transaction-manager-missing",
      severity: "medium",
      message: "The src/database/transaction-manager.ts file is missing. An AsyncLocalStorage-based TransactionManager is required.",
      docRef: DOC7
    });
    score -= penaltyFor("medium");
  }
  return {
    name: "database-queries",
    score: Math.max(score, 0),
    maxScore: 20,
    failures
  };
}

// evaluators/rules/domain-service.evaluator.ts
var fs29 = __toESM(require("node:fs"));
var path30 = __toESM(require("node:path"));
var DOC8 = "../../docs/architecture/domain-service.md";
function walkTsFiles6(root) {
  const out = [];
  if (!fs29.existsSync(root)) return out;
  for (const entry of fs29.readdirSync(root)) {
    if (["node_modules", "dist", "coverage", ".git"].includes(entry)) continue;
    const full = path30.join(root, entry);
    if (fs29.statSync(full).isDirectory()) {
      out.push(...walkTsFiles6(full));
      continue;
    }
    if (full.endsWith(".ts") && !full.endsWith(".d.ts") && !full.endsWith(".spec.ts")) out.push(full);
  }
  return out;
}
function isDomainServiceFile(root, file) {
  const rel = path30.relative(root, file).replace(/\\/g, "/");
  return /^src\/[^/]+\/domain\/[^/]+-service\.ts$/.test(rel);
}
function evaluateDomainService(root) {
  const srcRoot = path30.join(root, "src");
  const files = walkTsFiles6(srcRoot);
  const domainServiceFiles = files.filter((f) => isDomainServiceFile(root, f));
  if (domainServiceFiles.length === 0) {
    return { name: "domain-service", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 10;
  const rel = (f) => path30.relative(root, f);
  for (const file of domainServiceFiles) {
    const content = fs29.readFileSync(file, "utf-8");
    if (/@Injectable\s*\(\s*\)/.test(content)) {
      failures.push({
        ruleId: "domain-service.injectable-forbidden",
        severity: "high",
        message: `${rel(file)} must not use @Injectable(). A Domain Service must not depend on the NestJS framework.`,
        docRef: DOC8
      });
      score -= penaltyFor("high");
    }
    if (/@(Module|Controller|Get|Post|Put|Patch|Delete)\s*\(/.test(content)) {
      failures.push({
        ruleId: "domain-service.nestjs-decorator-forbidden",
        severity: "high",
        message: `${rel(file)} must not use NestJS routing/module decorators. A Domain Service must be a plain TypeScript class.`,
        docRef: DOC8
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "domain-service",
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  };
}

// evaluators/rules/aggregate-id.evaluator.ts
var fs30 = __toESM(require("node:fs"));
var path31 = __toESM(require("node:path"));
var DOC9 = "docs/architecture/aggregate-id.md";
function walkTsFiles7(root) {
  const out = [];
  if (!fs30.existsSync(root)) return out;
  for (const entry of fs30.readdirSync(root)) {
    if (["node_modules", "dist", "coverage", ".git"].includes(entry)) continue;
    const full = path31.join(root, entry);
    if (fs30.statSync(full).isDirectory()) {
      out.push(...walkTsFiles7(full));
      continue;
    }
    if (full.endsWith(".ts") && !full.endsWith(".d.ts") && !full.endsWith(".spec.ts")) out.push(full);
  }
  return out;
}
function evaluateAggregateId(root) {
  const srcRoot = path31.join(root, "src");
  const files = walkTsFiles7(srcRoot);
  const entityFiles = files.filter((f) => f.endsWith(".entity.ts"));
  if (entityFiles.length === 0) {
    return { name: "aggregate-id", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path31.relative(root, f);
  for (const file of entityFiles) {
    const content = fs30.readFileSync(file, "utf-8");
    if (/@PrimaryGeneratedColumn\s*\(/.test(content)) {
      failures.push({
        ruleId: "aggregate-id.primary-generated-column-forbidden",
        severity: "high",
        message: `${rel(file)} must not use @PrimaryGeneratedColumn() \u2014 generate the ID in the application with generateId() and use @PrimaryColumn({ type: 'char', length: 32 }).`,
        docRef: DOC9
      });
      score -= penaltyFor("high");
      continue;
    }
    if (/@PrimaryColumn\s*\(/.test(content)) {
      const primaryColMatch = /@PrimaryColumn\s*\(([^)]*)\)/.exec(content);
      if (primaryColMatch) {
        const opts = primaryColMatch[1];
        const hasChar = /type\s*:\s*['"]char['"]/.test(opts);
        const hasLength = /length\s*:\s*32\b/.test(opts);
        if (!hasChar || !hasLength) {
          failures.push({
            ruleId: "aggregate-id.primary-column-type",
            severity: "medium",
            message: `${rel(file)}'s @PrimaryColumn options are not { type: 'char', length: 32 }. Use the char(32) type for Aggregate IDs.`,
            docRef: DOC9
          });
          score -= penaltyFor("medium");
        }
      }
    }
  }
  const generateIdFile = files.find((f) => {
    const content = fs30.readFileSync(f, "utf-8");
    return /export\s+(function\s+generateId|const\s+generateId)/.test(content);
  });
  if (!generateIdFile) {
    failures.push({
      ruleId: "aggregate-id.generate-id-missing",
      severity: "medium",
      message: "The generateId() function is missing. Create a crypto.randomUUID()-based ID generation function at src/common/generate-id.ts.",
      docRef: DOC9
    });
    score -= penaltyFor("medium");
  } else {
    const content = fs30.readFileSync(generateIdFile, "utf-8");
    const stripsHyphens = /\.replace\s*\(\s*\/-\/g\s*,\s*(['"`])\1\s*\)/.test(content) || /\.replaceAll\s*\(\s*['"`]-['"`]\s*,\s*(['"`])\1\s*\)/.test(content);
    if (/randomUUID\s*\(/.test(content) && !stripsHyphens) {
      failures.push({
        ruleId: "aggregate-id.generate-id-raw-uuid",
        severity: "high",
        message: `${rel(generateIdFile)}'s generateId() returns randomUUID() as-is without stripping hyphens \u2014 use .replace(/-/g, '') to strip the hyphens and produce a 32-character hex string.`,
        docRef: DOC9
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "aggregate-id",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/domain-layer-isolation.evaluator.ts
var path32 = __toESM(require("node:path"));
var DOC_REF7 = "docs/architecture/layer-architecture.md#domain-layer-responsibilities";
var FORBIDDEN_TARGETS = /* @__PURE__ */ new Set(["application", "infrastructure", "interface"]);
function evaluateDomainLayerIsolation(root) {
  const srcRoot = path32.join(root, "src");
  const domainFiles = walkTsFiles(srcRoot).filter((f) => classifyLayer(f) === "domain" && !f.endsWith(".spec.ts"));
  if (domainFiles.length === 0) {
    return { name: "domain-layer-isolation", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 20;
  const rel = (f) => path32.relative(root, f);
  for (const file of domainFiles) {
    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier);
      if (!resolved) continue;
      const targetLayer = classifyLayer(resolved);
      if (!FORBIDDEN_TARGETS.has(targetLayer)) continue;
      failures.push({
        ruleId: "domain-layer-isolation.forbidden-import",
        severity: "high",
        message: `${rel(file)} \u2014 the domain layer imports the ${targetLayer} layer: '${specifier}'. Domain must not depend on any other layer`,
        docRef: DOC_REF7
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "domain-layer-isolation",
    score: Math.max(score, 0),
    maxScore: 20,
    failures
  };
}

// evaluators/rules/interface-no-infrastructure.evaluator.ts
var path33 = __toESM(require("node:path"));
var DOC_REF8 = "docs/architecture/layer-architecture.md#interface-layer-responsibilities";
function evaluateInterfaceNoInfrastructure(root) {
  const srcRoot = path33.join(root, "src");
  const interfaceFiles = walkTsFiles(srcRoot).filter((f) => {
    if (classifyLayer(f) !== "interface" || f.endsWith(".spec.ts")) return false;
    const domain = domainSegment(root, f);
    return domain !== null && isDomainBearing(root, domain);
  });
  if (interfaceFiles.length === 0) {
    return { name: "interface-no-infrastructure", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path33.relative(root, f);
  for (const file of interfaceFiles) {
    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier);
      if (!resolved) continue;
      if (classifyLayer(resolved) !== "infrastructure") continue;
      failures.push({
        ruleId: "interface-no-infrastructure.forbidden-import",
        severity: "high",
        message: `${rel(file)} \u2014 the Controller imports infrastructure directly: '${specifier}'. Access must go only through an Application Service`,
        docRef: DOC_REF8
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "interface-no-infrastructure",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/aggregate-no-public-setters.evaluator.ts
var path34 = __toESM(require("node:path"));
var import_typescript5 = __toESM(require("typescript"));
var DOC_REF9 = "docs/architecture/layer-architecture.md#domain-layer-responsibilities";
function hasModifier(mods, kind) {
  return (mods ?? []).some((m) => m.kind === kind);
}
function inspectFile(filePath) {
  const sf = readSourceFile(filePath);
  const violations = [];
  function visit(node) {
    if (import_typescript5.default.isClassDeclaration(node) && node.name) {
      for (const member of node.members) {
        const mods = member.modifiers;
        const isPrivate = hasModifier(mods, import_typescript5.default.SyntaxKind.PrivateKeyword);
        const isProtected = hasModifier(mods, import_typescript5.default.SyntaxKind.ProtectedKeyword);
        const isStatic = hasModifier(mods, import_typescript5.default.SyntaxKind.StaticKeyword);
        if (import_typescript5.default.isSetAccessor(member) && member.name && !isPrivate && !isProtected) {
          const name = member.name.getText(sf);
          violations.push({
            ruleId: "aggregate-no-public-setters.public-setter",
            message: `${node.name.text}.${name}(...) \u2014 public setters are forbidden. Expose state changes only through named domain methods`
          });
        }
        if (import_typescript5.default.isPropertyDeclaration(member) && member.name && import_typescript5.default.isIdentifier(member.name)) {
          const isReadonly = hasModifier(mods, import_typescript5.default.SyntaxKind.ReadonlyKeyword);
          if (!isPrivate && !isProtected && !isReadonly && !isStatic) {
            violations.push({
              ruleId: "aggregate-no-public-setters.public-mutable-field",
              message: `${node.name.text}.${member.name.text} \u2014 public mutable fields are forbidden. Hide it as private and change it only via readonly or a domain method`
            });
          }
        }
      }
    }
    import_typescript5.default.forEachChild(node, visit);
  }
  visit(sf);
  return violations;
}
function evaluateAggregateNoPublicSetters(root) {
  const srcRoot = path34.join(root, "src");
  const domainFiles = walkTsFiles(srcRoot).filter((f) => classifyLayer(f) === "domain" && !f.endsWith(".spec.ts"));
  if (domainFiles.length === 0) {
    return { name: "aggregate-no-public-setters", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path34.relative(root, f);
  for (const file of domainFiles) {
    for (const v of inspectFile(file)) {
      failures.push({
        ruleId: v.ruleId,
        severity: "high",
        message: `${rel(file)} \u2014 ${v.message}`,
        docRef: DOC_REF9
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "aggregate-no-public-setters",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/no-cross-aggregate-reference.evaluator.ts
var fs31 = __toESM(require("node:fs"));
var path35 = __toESM(require("node:path"));
var import_typescript6 = __toESM(require("typescript"));
var DOC_REF10 = "../../docs/architecture/domain-service.md#a-real-working-example--refundeligibilityservice-coordinating-across-aggregates";
function importsNamedBindingFrom(filePath, forbiddenName, moduleSuffix) {
  const sf = readSourceFile(filePath);
  let found = false;
  sf.forEachChild((node) => {
    if (!import_typescript6.default.isImportDeclaration(node) || !import_typescript6.default.isStringLiteral(node.moduleSpecifier)) return;
    const specifier = node.moduleSpecifier.text;
    if (!specifier.endsWith(moduleSuffix)) return;
    const clause = node.importClause;
    const namedBindings = clause?.namedBindings;
    if (namedBindings && import_typescript6.default.isNamedImports(namedBindings)) {
      for (const el of namedBindings.elements) {
        if (el.name.text === forbiddenName) found = true;
      }
    }
  });
  return found;
}
function evaluateNoCrossAggregateReference(root) {
  const paymentFile = path35.join(root, "src", "payment", "domain", "payment.ts");
  const refundFile = path35.join(root, "src", "payment", "domain", "refund.ts");
  if (!fs31.existsSync(paymentFile) || !fs31.existsSync(refundFile)) {
    return { name: "no-cross-aggregate-reference", score: 0, maxScore: 0, failures: [] };
  }
  const pairs = [
    { file: paymentFile, forbiddenName: "Refund", forbiddenModuleSuffix: "/refund" },
    { file: refundFile, forbiddenName: "Payment", forbiddenModuleSuffix: "/payment" }
  ];
  const failures = [];
  let score = 10;
  const rel = (f) => path35.relative(root, f);
  for (const pair of pairs) {
    if (!importsNamedBindingFrom(pair.file, pair.forbiddenName, pair.forbiddenModuleSuffix)) continue;
    failures.push({
      ruleId: "no-cross-aggregate-reference.aggregate-field-reference",
      severity: "high",
      message: `${rel(pair.file)} \u2014 directly imports another Aggregate (${pair.forbiddenName}). Only an ID reference (e.g. ${pair.forbiddenName.toLowerCase()}Id: string) is allowed`,
      docRef: DOC_REF10
    });
    score -= penaltyFor("high");
  }
  return {
    name: "no-cross-aggregate-reference",
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  };
}

// evaluators/rules/no-cross-bc-repository-in-application.evaluator.ts
var path36 = __toESM(require("node:path"));
var DOC_REF11 = "../../docs/architecture/cross-domain-communication.md#synchronous-calls--the-adapter-pattern-acl";
function isRepositoryFile(filePath) {
  return /-repository(\.ts)?$/.test(filePath.replace(/\\/g, "/")) && classifyLayer(filePath) === "domain";
}
function evaluateNoCrossBcRepositoryInApplication(root) {
  const srcRoot = path36.join(root, "src");
  const allFiles = walkTsFiles(srcRoot);
  const domainBearingCount = new Set(
    allFiles.map((f) => domainSegment(root, f)).filter((d) => d !== null && isDomainBearing(root, d))
  ).size;
  const applicationFiles = allFiles.filter((f) => classifyLayer(f) === "application" && !f.endsWith(".spec.ts"));
  if (domainBearingCount < 2 || applicationFiles.length === 0) {
    return { name: "no-cross-bc-repository-in-application", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path36.relative(root, f);
  for (const file of applicationFiles) {
    const ownDomain = domainSegment(root, file);
    if (!ownDomain) continue;
    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier);
      if (!resolved || !isRepositoryFile(resolved)) continue;
      const targetDomain = domainSegment(root, resolved);
      if (!targetDomain || targetDomain === ownDomain) continue;
      failures.push({
        ruleId: "no-cross-bc-repository-in-application.cross-domain-repository-import",
        severity: "high",
        message: `${rel(file)} (${ownDomain}) \u2014 directly imports another BC's (${targetDomain}) Repository: '${specifier}'. It must go through an Adapter (ACL)`,
        docRef: DOC_REF11
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "no-cross-bc-repository-in-application",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/soft-delete-filter.evaluator.ts
var fs32 = __toESM(require("node:fs"));
var path37 = __toESM(require("node:path"));
var import_typescript7 = __toESM(require("typescript"));
var DOC10 = "docs/architecture/persistence.md#soft-delete";
function classNamesDefinedIn(content) {
  return [...content.matchAll(/export\s+class\s+(\w+)/g)].map((m) => m[1]);
}
function isSoftDeletable(content) {
  return /extends\s+\w*BaseEntity/.test(content) || /@DeleteDateColumn\s*\(/.test(content);
}
function injectedEntityNames(filePath) {
  const sf = readSourceFile(filePath);
  const names = [];
  function visit(node) {
    if (import_typescript7.default.isConstructorDeclaration(node)) {
      for (const p of node.parameters) {
        const mods = p.modifiers ?? [];
        for (const mod of mods) {
          if (!import_typescript7.default.isDecorator(mod)) continue;
          const expr = mod.expression;
          if (import_typescript7.default.isCallExpression(expr) && import_typescript7.default.isIdentifier(expr.expression) && expr.expression.text === "InjectRepository" && expr.arguments[0] && import_typescript7.default.isIdentifier(expr.arguments[0])) {
            names.push(expr.arguments[0].text);
          }
        }
      }
    }
    import_typescript7.default.forEachChild(node, visit);
  }
  visit(sf);
  return names;
}
function hasManualDeletedAtFilter(content) {
  return /deletedAt[^;]{0,40}(IsNull\s*\(\s*\)|is\s+null)/i.test(content);
}
function evaluateSoftDeleteFilter(root) {
  const srcRoot = path37.join(root, "src");
  const allFiles = walkTsFiles(srcRoot).filter((f) => !f.endsWith(".spec.ts"));
  const repoImplFiles = allFiles.filter((f) => /-repository-impl\.ts$/.test(f));
  if (repoImplFiles.length === 0) {
    return { name: "soft-delete-filter", score: 0, maxScore: 0, failures: [] };
  }
  const entityFiles = allFiles.filter((f) => f.endsWith(".entity.ts"));
  const entitySoftDeleteMap = /* @__PURE__ */ new Map();
  for (const ef of entityFiles) {
    const content = fs32.readFileSync(ef, "utf-8");
    for (const cls of classNamesDefinedIn(content)) {
      entitySoftDeleteMap.set(cls, isSoftDeletable(content));
    }
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path37.relative(root, f);
  for (const file of repoImplFiles) {
    const content = fs32.readFileSync(file, "utf-8");
    const hasFindMethod = /\basync\s+find[A-Z]\w*\s*\(/.test(content);
    if (!hasFindMethod) continue;
    const entities = injectedEntityNames(file);
    const manuallyFiltered = hasManualDeletedAtFilter(content);
    for (const entityName of entities) {
      if (!entitySoftDeleteMap.has(entityName)) continue;
      if (entitySoftDeleteMap.get(entityName)) continue;
      if (manuallyFiltered) continue;
      failures.push({
        ruleId: "soft-delete-filter.entity-not-soft-deletable",
        severity: "high",
        message: `${rel(file)} \u2014 ${entityName}, which the find method queries, is not soft-delete-capable (does not extend BaseEntity, has no @DeleteDateColumn). Deleted rows may leak into the query results \u2014 add @DeleteDateColumn to the Entity (extend BaseEntity) or filter deletedAt IS NULL directly in the Repository.`,
        docRef: DOC10
      });
      score -= penaltyFor("high");
    }
    const softDeletableEntityNames = entities.filter((e) => entitySoftDeleteMap.get(e));
    if (softDeletableEntityNames.length > 0) {
      const rawQueryMatches = [...content.matchAll(/\.query\s*\(\s*(`[\s\S]*?`|'[^']*'|"[^"]*")/g)];
      for (const m of rawQueryMatches) {
        const sql = m[1];
        if (!/deleted_?at\s+is\s+null/i.test(sql)) {
          const line = content.slice(0, m.index ?? 0).split("\n").length;
          failures.push({
            ruleId: "soft-delete-filter.raw-query-missing-filter",
            severity: "high",
            message: `${rel(file)}:${line} \u2014 raw SQL (.query()) queries a soft-delete-capable Entity (${softDeletableEntityNames.join(", ")}) without a deletedAt IS NULL filter. Raw SQL bypasses TypeORM's automatic soft-delete filter, so it must be filtered manually.`,
            docRef: DOC10
          });
          score -= penaltyFor("high");
        }
      }
    }
  }
  return {
    name: "soft-delete-filter",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/no-generic-response-keys.evaluator.ts
var path38 = __toESM(require("node:path"));
var import_typescript8 = __toESM(require("typescript"));
var DOC_REF12 = "../../docs/architecture/api-response.md#list-lookup-response-format";
var GENERIC_KEYS = /* @__PURE__ */ new Set(["result", "data", "items"]);
function propertyTypeText(member, sf) {
  if (!import_typescript8.default.isPropertyDeclaration(member) || !member.type) return null;
  return member.type.getText(sf);
}
function isArrayTypeText(typeText) {
  return /\[\]\s*$/.test(typeText) || /^Array\s*</.test(typeText);
}
function inspectFile2(filePath) {
  const sf = readSourceFile(filePath);
  const violations = [];
  function visit(node) {
    if (import_typescript8.default.isClassDeclaration(node) && node.name) {
      const propertyNames = node.members.filter((m) => import_typescript8.default.isPropertyDeclaration(m) && !!m.name && import_typescript8.default.isIdentifier(m.name)).map((m) => m.name.text);
      const hasCount = propertyNames.includes("count");
      if (hasCount) {
        for (const member of node.members) {
          if (!import_typescript8.default.isPropertyDeclaration(member) || !member.name || !import_typescript8.default.isIdentifier(member.name)) continue;
          const name = member.name.text;
          if (!GENERIC_KEYS.has(name)) continue;
          const typeText = propertyTypeText(member, sf);
          if (!typeText || !isArrayTypeText(typeText)) continue;
          violations.push({
            ruleId: "no-generic-response-keys.generic-list-field",
            message: `${node.name.text}.${name} \u2014 a generic key (${name}) must not be used for a list response's array field. Use a domain-specific plural noun instead (e.g. orders, accounts)`
          });
        }
      }
    }
    import_typescript8.default.forEachChild(node, visit);
  }
  visit(sf);
  return violations;
}
function evaluateNoGenericResponseKeys(root) {
  const srcRoot = path38.join(root, "src");
  const targetFiles = walkTsFiles(srcRoot).filter((f) => {
    const layer = classifyLayer(f);
    return (layer === "application" || layer === "interface") && !f.endsWith(".spec.ts");
  });
  if (targetFiles.length === 0) {
    return { name: "no-generic-response-keys", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path38.relative(root, f);
  for (const file of targetFiles) {
    for (const v of inspectFile2(file)) {
      failures.push({
        ruleId: v.ruleId,
        severity: "medium",
        message: `${rel(file)} \u2014 ${v.message}`,
        docRef: DOC_REF12
      });
      score -= penaltyFor("medium");
    }
  }
  return {
    name: "no-generic-response-keys",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/query-handler-no-raw-aggregate.evaluator.ts
var path39 = __toESM(require("node:path"));
var import_typescript9 = __toESM(require("typescript"));
var DOC_REF13 = "../../docs/architecture/api-response.md#result-object-design";
function isRepositoryDomainFile2(filePath) {
  const normalized = filePath.replace(/\\/g, "/");
  return classifyLayer(filePath) === "domain" && /-repository\.ts$/.test(normalized);
}
function extractAggregateNamesFromRepository(filePath) {
  const sf = readSourceFile(filePath);
  const names = [];
  function visit(node) {
    if (import_typescript9.default.isMethodDeclaration(node) && node.name && import_typescript9.default.isIdentifier(node.name) && /^save([A-Z]|$)/.test(node.name.text)) {
      const param = node.parameters[0];
      if (param?.type && import_typescript9.default.isTypeReferenceNode(param.type)) {
        names.push(param.type.typeName.getText(sf));
      }
    }
    import_typescript9.default.forEachChild(node, visit);
  }
  visit(sf);
  return names;
}
function extractBareTypeCandidates(typeText) {
  return typeText.split("|").map((part) => part.trim()).map((part) => part.replace(/\[\]\s*$/, "")).map((part) => {
    const arrayGeneric = part.match(/^Array\s*<\s*(.+)\s*>$/);
    return arrayGeneric ? arrayGeneric[1].trim() : part;
  }).filter(Boolean);
}
function inspectFile3(filePath, aggregateNames) {
  const sf = readSourceFile(filePath);
  const violations = [];
  function checkReturnType(typeNode, ownerLabel, memberLabel) {
    if (!typeNode) return;
    let innerText = null;
    if (import_typescript9.default.isTypeReferenceNode(typeNode) && typeNode.typeName.getText(sf) === "Promise" && typeNode.typeArguments?.[0]) {
      innerText = typeNode.typeArguments[0].getText(sf);
    }
    if (!innerText) return;
    for (const candidate of extractBareTypeCandidates(innerText)) {
      if (aggregateNames.has(candidate)) {
        violations.push({
          ruleId: "query-handler-no-raw-aggregate.raw-aggregate-return",
          message: `${ownerLabel}.${memberLabel} \u2014 returns Promise<${innerText}>. Must not return the Domain Aggregate (${candidate}) as-is; wrap it in a dedicated Result/DTO type`
        });
      }
    }
  }
  function visit(node) {
    if (import_typescript9.default.isClassDeclaration(node) && node.name) {
      const className = node.name.text;
      for (const clause of node.heritageClauses ?? []) {
        if (clause.token !== import_typescript9.default.SyntaxKind.ImplementsKeyword) continue;
        for (const t of clause.types) {
          if (t.expression.getText(sf) !== "IQueryHandler") continue;
          const resultArg = t.typeArguments?.[1];
          if (!resultArg) continue;
          for (const candidate of extractBareTypeCandidates(resultArg.getText(sf))) {
            if (aggregateNames.has(candidate)) {
              violations.push({
                ruleId: "query-handler-no-raw-aggregate.raw-aggregate-return",
                message: `${className} \u2014 returns IQueryHandler<..., ${resultArg.getText(sf)}>. Must not return the Domain Aggregate (${candidate}) as-is; wrap it in a dedicated Result/DTO type`
              });
            }
          }
        }
      }
      for (const member of node.members) {
        if (!import_typescript9.default.isMethodDeclaration(member) || !member.name) continue;
        const memberName = import_typescript9.default.isIdentifier(member.name) ? member.name.text : member.name.getText(sf);
        checkReturnType(member.type, className, memberName);
      }
    }
    import_typescript9.default.forEachChild(node, visit);
  }
  visit(sf);
  return violations;
}
function evaluateQueryHandlerNoRawAggregate(root) {
  const srcRoot = path39.join(root, "src");
  const allFiles = walkTsFiles(srcRoot);
  const aggregateNames = /* @__PURE__ */ new Set();
  for (const file of allFiles.filter(isRepositoryDomainFile2)) {
    for (const name of extractAggregateNamesFromRepository(file)) aggregateNames.add(name);
  }
  const targetFiles = allFiles.filter((f) => {
    if (f.endsWith(".spec.ts")) return false;
    const layer = classifyLayer(f);
    if (layer === "interface") return true;
    return layer === "application" && f.replace(/\\/g, "/").includes("/query/");
  });
  if (aggregateNames.size === 0 || targetFiles.length === 0) {
    return { name: "query-handler-no-raw-aggregate", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path39.relative(root, f);
  for (const file of targetFiles) {
    for (const v of inspectFile3(file, aggregateNames)) {
      failures.push({
        ruleId: v.ruleId,
        severity: "high",
        message: `${rel(file)} \u2014 ${v.message}`,
        docRef: DOC_REF13
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "query-handler-no-raw-aggregate",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/no-cross-bc-domain-import.evaluator.ts
var path40 = __toESM(require("node:path"));
var DOC_REF14 = "../../docs/architecture/tactical-ddd.md#aggregate-root";
function evaluateNoCrossBcDomainImport(root) {
  const srcRoot = path40.join(root, "src");
  const allFiles = walkTsFiles(srcRoot);
  const domainBearingBcs = new Set(
    allFiles.map((f) => domainSegment(root, f)).filter((d) => d !== null && isDomainBearing(root, d))
  );
  const domainFiles = allFiles.filter((f) => classifyLayer(f) === "domain" && !f.endsWith(".spec.ts"));
  if (domainBearingBcs.size < 2 || domainFiles.length === 0) {
    return { name: "no-cross-bc-domain-import", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 15;
  const rel = (f) => path40.relative(root, f);
  for (const file of domainFiles) {
    const ownBc = domainSegment(root, file);
    if (!ownBc) continue;
    for (const specifier of parseImports(file)) {
      const resolved = resolveImportPath(root, file, specifier);
      if (!resolved || classifyLayer(resolved) !== "domain") continue;
      const targetBc = domainSegment(root, resolved);
      if (!targetBc || targetBc === ownBc || !domainBearingBcs.has(targetBc)) continue;
      failures.push({
        ruleId: "no-cross-bc-domain-import.cross-bc-domain-import",
        severity: "high",
        message: `${rel(file)} (${ownBc}) \u2014 directly imports another BC's (${targetBc}) domain/: '${specifier}'. Another Aggregate may only be referenced by ID (object references are forbidden)`,
        docRef: DOC_REF14
      });
      score -= penaltyFor("high");
    }
  }
  return {
    name: "no-cross-bc-domain-import",
    score: Math.max(score, 0),
    maxScore: 15,
    failures
  };
}

// evaluators/rules/no-orm-autosync-in-prod-config.evaluator.ts
var path41 = __toESM(require("node:path"));
var import_typescript10 = __toESM(require("typescript"));
var DOC_REF15 = "../../docs/architecture/persistence.md#migrations";
function isTypeOrmConfigCall(node, sf) {
  if (import_typescript10.default.isNewExpression(node)) {
    return node.expression.getText(sf) === "DataSource";
  }
  if (import_typescript10.default.isCallExpression(node) && import_typescript10.default.isPropertyAccessExpression(node.expression)) {
    const objectText = node.expression.expression.getText(sf);
    const methodName = node.expression.name.text;
    return objectText === "TypeOrmModule" && (methodName === "forRoot" || methodName === "forRootAsync");
  }
  return false;
}
function stringLiteralEquals(node, value) {
  return import_typescript10.default.isStringLiteral(node) && node.text === value;
}
function productionComparison(node) {
  const op = node.operatorToken.getText();
  const isEq = op === "===" || op === "==";
  const isNeq = op === "!==" || op === "!=";
  if (!isEq && !isNeq) return { matches: false, equalsWhenProd: false };
  if (!stringLiteralEquals(node.right, "production") && !stringLiteralEquals(node.left, "production")) {
    return { matches: false, equalsWhenProd: false };
  }
  return { matches: true, equalsWhenProd: isEq };
}
function evaluatesTrueInProduction(node, sf) {
  if (node.kind === import_typescript10.default.SyntaxKind.TrueKeyword) return true;
  if (node.kind === import_typescript10.default.SyntaxKind.FalseKeyword) return false;
  if (import_typescript10.default.isParenthesizedExpression(node)) return evaluatesTrueInProduction(node.expression, sf);
  if (import_typescript10.default.isBinaryExpression(node)) {
    const { matches, equalsWhenProd } = productionComparison(node);
    return matches ? equalsWhenProd : false;
  }
  if (import_typescript10.default.isConditionalExpression(node) && import_typescript10.default.isBinaryExpression(node.condition)) {
    const { matches, equalsWhenProd } = productionComparison(node.condition);
    if (!matches) return false;
    const activeBranch = equalsWhenProd ? node.whenTrue : node.whenFalse;
    return evaluatesTrueInProduction(activeBranch, sf);
  }
  return false;
}
function inspectSynchronizeProperty(prop, sf) {
  const value = prop.initializer;
  if (value.kind === import_typescript10.default.SyntaxKind.TrueKeyword) {
    return {
      ruleId: "no-orm-autosync-in-prod-config.synchronize-hardcoded-true",
      message: `synchronize: true \u2014 hardcoding this is forbidden. It must never be true in production; manage the schema with migrations instead`
    };
  }
  if (evaluatesTrueInProduction(value, sf)) {
    return {
      ruleId: "no-orm-autosync-in-prod-config.synchronize-true-in-production",
      message: `synchronize: ${value.getText(sf)} \u2014 evaluates to true when NODE_ENV=production. auto-schema-sync is forbidden in production`
    };
  }
  return null;
}
function findSynchronizeViolations(callNode, sf) {
  const violations = [];
  function visit(node) {
    if (import_typescript10.default.isPropertyAssignment(node) && (import_typescript10.default.isIdentifier(node.name) && node.name.text === "synchronize" || import_typescript10.default.isStringLiteral(node.name) && node.name.text === "synchronize")) {
      const v = inspectSynchronizeProperty(node, sf);
      if (v) violations.push(v);
    }
    import_typescript10.default.forEachChild(node, visit);
  }
  visit(callNode);
  return violations;
}
function evaluateNoOrmAutosyncInProdConfig(root) {
  const srcRoot = path41.join(root, "src");
  const files = walkTsFiles(srcRoot).filter((f) => !f.endsWith(".spec.ts"));
  const failures = [];
  let configCallCount = 0;
  const rel = (f) => path41.relative(root, f);
  for (const file of files) {
    let visit2 = function(node) {
      if (isTypeOrmConfigCall(node, sf)) {
        matched = true;
        for (const v of findSynchronizeViolations(node, sf)) {
          failures.push({
            ruleId: v.ruleId,
            severity: "critical",
            message: `${rel(file)} \u2014 ${v.message}`,
            docRef: DOC_REF15
          });
        }
      }
      import_typescript10.default.forEachChild(node, visit2);
    };
    var visit = visit2;
    const sf = readSourceFile(file);
    let matched = false;
    visit2(sf);
    if (matched) configCallCount += 1;
  }
  if (configCallCount === 0) {
    return { name: "no-orm-autosync-in-prod-config", score: 0, maxScore: 0, failures: [] };
  }
  let score = 10;
  for (const f of failures) score -= penaltyFor(f.severity);
  return {
    name: "no-orm-autosync-in-prod-config",
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  };
}

// evaluators/rules/api-documentation.evaluator.ts
var fs33 = __toESM(require("node:fs"));
var path42 = __toESM(require("node:path"));
var DOC11 = "docs/architecture/api-response.md#machine-readable-api-documentation-openapi";
var ROUTE_DECORATOR = /@(Get|Post|Put|Patch|Delete)\s*\(/g;
var TWO_XX_RESPONSE_DECORATORS = /^Api(Ok|Created|Accepted|NoContent)Response$/;
var ANY_API_RESPONSE_SHORTHAND = /@(Api\w+Response)\s*\(/g;
var GENERIC_NON_2XX_RESPONSE = /@ApiResponse\s*\(\s*\{[^}]*status:\s*[45]\d\d/;
function hasNon2xxResponse(block) {
  if (GENERIC_NON_2XX_RESPONSE.test(block)) return true;
  return [...block.matchAll(ANY_API_RESPONSE_SHORTHAND)].some(([, name]) => name !== "ApiResponse" && !TWO_XX_RESPONSE_DECORATORS.test(name));
}
function walkTsFiles8(root) {
  const out = [];
  if (!fs33.existsSync(root)) return out;
  for (const entry of fs33.readdirSync(root)) {
    if (["node_modules", "dist", "coverage", ".git"].includes(entry)) continue;
    const full = path42.join(root, entry);
    if (fs33.statSync(full).isDirectory()) {
      out.push(...walkTsFiles8(full));
      continue;
    }
    if (full.endsWith(".ts") && !full.endsWith(".spec.ts") && !full.endsWith(".d.ts")) out.push(full);
  }
  return out;
}
function splitIntoEndpointBlocks(content) {
  const matches = [...content.matchAll(ROUTE_DECORATOR)];
  return matches.map((m, i) => {
    const start = m.index ?? 0;
    const end = i + 1 < matches.length ? matches[i + 1].index ?? content.length : content.length;
    return { routeStart: start, block: content.slice(start, end) };
  });
}
function lineNumberAt(content, index) {
  return content.slice(0, index).split("\n").length;
}
function evaluateApiDocumentation(root) {
  const failures = [];
  let score = 30;
  const files = walkTsFiles8(path42.join(root, "src")).filter((f) => f.endsWith("-controller.ts")).filter((f) => fs33.readFileSync(f, "utf-8").includes("@Controller("));
  for (const file of files) {
    const content = fs33.readFileSync(file, "utf-8");
    const rel = path42.relative(root, file);
    const classHeaderEnd = content.search(/export\s+class/);
    const classLevelBlock = classHeaderEnd === -1 ? "" : content.slice(0, classHeaderEnd);
    const classHasNon2xx = hasNon2xxResponse(classLevelBlock);
    const endpoints = splitIntoEndpointBlocks(content);
    for (const { routeStart, block } of endpoints) {
      const line = lineNumberAt(content, routeStart);
      const routeMatch = block.match(/@(Get|Post|Put|Patch|Delete)\s*\(\s*(['"`][^'"`]*['"`])?/);
      const routeLabel = routeMatch ? `${routeMatch[1]} ${routeMatch[2] ?? ""}`.trim() : "route";
      const hasOperation = /@ApiOperation\s*\(\s*\{/.test(block);
      const opBlockMatch = block.match(/@ApiOperation\s*\(\s*\{([^]*?)\}\s*\)/);
      const opBody = opBlockMatch ? opBlockMatch[1] : "";
      const hasSummary = /summary\s*:/.test(opBody);
      const hasDescription = /description\s*:/.test(opBody);
      if (!hasOperation || !hasSummary || !hasDescription) {
        failures.push({
          ruleId: "api-documentation.operation-incomplete",
          severity: "medium",
          message: `${rel}:${line} \u2014 ${routeLabel} is missing @ApiOperation with both summary and description (an operationId alone is not sufficient)`,
          docRef: DOC11
        });
        score -= 3;
      }
      if (!classHasNon2xx && !hasNon2xxResponse(block)) {
        failures.push({
          ruleId: "api-documentation.error-response-undocumented",
          severity: "medium",
          message: `${rel}:${line} \u2014 ${routeLabel} documents only the success response; no non-2xx response (e.g. @ApiNotFoundResponse, @ApiBadRequestResponse) is declared`,
          docRef: DOC11
        });
        score -= 3;
      }
    }
  }
  return {
    name: "api-documentation",
    score: Math.max(score, 0),
    maxScore: 30,
    failures
  };
}

// evaluators/rules/user-context-store.evaluator.ts
var fs34 = __toESM(require("node:fs"));
var path43 = __toESM(require("node:path"));
var DOC_REF16 = "docs/architecture/authentication.md";
var DIRECT_USER_ACCESS = /\b(req|request)\.user\b/g;
function walkFiles3(root) {
  const out = [];
  if (!fs34.existsSync(root)) return out;
  for (const entry of fs34.readdirSync(root)) {
    if (entry === "node_modules" || entry === "dist" || entry === "coverage" || entry === ".git") continue;
    const fullPath = path43.join(root, entry);
    const stat = fs34.statSync(fullPath);
    if (stat.isDirectory()) {
      out.push(...walkFiles3(fullPath));
      continue;
    }
    if (fullPath.endsWith("controller.ts") && !fullPath.endsWith(".spec.ts")) out.push(fullPath);
  }
  return out;
}
function lineOf3(source, index) {
  return source.slice(0, index).split("\n").length;
}
function evaluateUserContextStore(root) {
  const controllerFiles = walkFiles3(path43.join(root, "src"));
  if (controllerFiles.length === 0) {
    return { name: "user-context-store", score: 0, maxScore: 0, failures: [] };
  }
  const failures = [];
  let score = 10;
  const rel = (file) => path43.relative(root, file);
  for (const file of controllerFiles) {
    const content = fs34.readFileSync(file, "utf-8");
    DIRECT_USER_ACCESS.lastIndex = 0;
    let match;
    while ((match = DIRECT_USER_ACCESS.exec(content)) !== null) {
      failures.push({
        ruleId: "user-context-store.direct-request-access-forbidden",
        severity: "medium",
        message: `${rel(file)}:${lineOf3(content, match.index)} reads the authenticated user directly off the request object (${match[0]}) \u2014 use UserContextStore.getRequesterId()/getUser() instead`,
        docRef: DOC_REF16
      });
      score -= 3;
    }
  }
  return {
    name: "user-context-store",
    score: Math.max(score, 0),
    maxScore: 10,
    failures
  };
}

// evaluators/rules/timezone-pin.evaluator.ts
var fs35 = __toESM(require("node:fs"));
var path44 = __toESM(require("node:path"));
var import_typescript11 = __toESM(require("typescript"));
var CONVENTIONS_DOC_REF = "docs/conventions.md";
var BOOTSTRAP_DOC_REF2 = "docs/architecture/bootstrap.md";
var UTC_ZONE_NAMES = /* @__PURE__ */ new Set(["UTC", "Etc/UTC", "Etc/GMT", "GMT"]);
function resolveProjectFile(root, fromFile, specifier) {
  const base = resolveImportPath(root, fromFile, specifier);
  if (!base) return null;
  for (const candidate of [`${base}.ts`, path44.join(base, "index.ts")]) {
    if (fs35.existsSync(candidate) && fs35.statSync(candidate).isFile()) return candidate;
  }
  return null;
}
function importSpecifiers(sf) {
  const specifiers = [];
  sf.forEachChild((node) => {
    if (import_typescript11.default.isImportDeclaration(node) && import_typescript11.default.isStringLiteral(node.moduleSpecifier)) {
      specifiers.push(node.moduleSpecifier.text);
    }
  });
  return specifiers;
}
function isProcessEnvTz(node, sf) {
  if (import_typescript11.default.isPropertyAccessExpression(node)) {
    return node.name.text === "TZ" && node.expression.getText(sf) === "process.env";
  }
  if (import_typescript11.default.isElementAccessExpression(node)) {
    const arg = node.argumentExpression;
    return import_typescript11.default.isStringLiteral(arg) && arg.text === "TZ" && node.expression.getText(sf) === "process.env";
  }
  return false;
}
function findPin(file) {
  const sf = readSourceFile(file);
  let pin = null;
  function visit(node) {
    if (pin === null && import_typescript11.default.isBinaryExpression(node) && node.operatorToken.kind === import_typescript11.default.SyntaxKind.EqualsToken && isProcessEnvTz(node.left, sf) && import_typescript11.default.isStringLiteral(node.right)) {
      pin = { file, zone: node.right.text };
      return;
    }
    if (pin === null) import_typescript11.default.forEachChild(node, visit);
  }
  visit(sf);
  return pin;
}
function evaluateTimezonePin(root) {
  const mainPath = path44.join(root, "src", "main.ts");
  if (!fs35.existsSync(mainPath)) {
    return { name: "timezone-pin", score: 0, maxScore: 0, failures: [] };
  }
  const rel = (file) => path44.relative(root, file).replace(/\\/g, "/");
  const failures = [];
  const mainSource = readSourceFile(mainPath);
  const specifiers = importSpecifiers(mainSource);
  const importedFiles = specifiers.map((specifier) => resolveProjectFile(root, mainPath, specifier)).filter((file) => file !== null);
  const firstImportedFile = specifiers.length > 0 ? resolveProjectFile(root, mainPath, specifiers[0]) : null;
  let pin = null;
  for (const file of [mainPath, ...importedFiles]) {
    pin = findPin(file);
    if (pin) break;
  }
  if (!pin) {
    failures.push({
      ruleId: "timezone-pin.missing",
      severity: "high",
      message: "src/main.ts \u2014 the bootstrap never pins the process timezone. Timestamp columns are TIMESTAMP (WITHOUT TIME ZONE), so the driver stores the host's wall clock and the same write means a different instant per machine. Set process.env.TZ = 'UTC' in a side-effect module imported first by main.ts",
      docRef: CONVENTIONS_DOC_REF
    });
  } else if (!UTC_ZONE_NAMES.has(pin.zone)) {
    failures.push({
      ruleId: "timezone-pin.non-utc",
      severity: "critical",
      message: `${rel(pin.file)} \u2014 the process timezone is pinned to '${pin.zone}'. Timestamps are persisted as the UTC instant; pinning any other zone writes a shifted wall clock into the TIMESTAMP (WITHOUT TIME ZONE) columns. Pin 'UTC'`,
      docRef: CONVENTIONS_DOC_REF
    });
  } else if (pin.file !== firstImportedFile) {
    failures.push({
      ruleId: "timezone-pin.not-first-import",
      severity: "medium",
      message: `${rel(pin.file)} \u2014 the UTC pin is not applied by main.ts's first import${firstImportedFile ? ` (that is ${rel(firstImportedFile)})` : ""}. Node applies a change to process.env.TZ only to date operations that follow it, and module loading hoists every import above any inline statement \u2014 so the pin must live in the module main.ts imports first, ahead of anything that can stamp a time or open a pool`,
      docRef: BOOTSTRAP_DOC_REF2
    });
  }
  let score = 10;
  for (const failure of failures) score -= penaltyFor(failure.severity);
  return { name: "timezone-pin", score: Math.max(score, 0), maxScore: 10, failures };
}

// evaluators/shared/score.ts
function aggregate(results) {
  const breakdown = { structure: 0, architecture: 0, runtime: 0, testing: 0, api: 0, semantics: 0 };
  const breakdownMax = { structure: 0, architecture: 0, runtime: 0, testing: 0, api: 0, semantics: 0 };
  let rawScore = 0;
  let rawMax = 0;
  const failures = [];
  const skippedEvaluators = [];
  for (const r of results) {
    failures.push(...r.failures);
    if (r.maxScore <= 0) {
      skippedEvaluators.push(r.name);
      continue;
    }
    rawScore += r.score;
    rawMax += r.maxScore;
    const bucket = (() => {
      if (r.name.includes("structure") || r.name === "file-naming") return "structure";
      if (r.name.includes("layer") || r.name.includes("repository") || r.name.includes("checklist") || r.name.includes("task-queue") || r.name.includes("scheduler") || r.name.includes("cqrs") || r.name.includes("error-handling") || r.name.includes("module-di") || r.name.includes("import-graph") || r.name.includes("domain-event-outbox") || r.name.includes("domain-layer-isolation") || r.name.includes("interface-no-infrastructure") || r.name.includes("aggregate-no-public-setters") || r.name.includes("no-cross-aggregate-reference") || r.name.includes("no-cross-bc-repository-in-application") || r.name.includes("soft-delete-filter") || r.name.includes("query-handler-no-raw-aggregate") || r.name.includes("no-cross-bc-domain-import") || r.name === "user-context-store") return "architecture";
      if (r.name === "build" || r.name === "test-run" || r.name === "secret-manager" || r.name === "dockerfile" || r.name === "local-dev" || r.name === "no-orm-autosync-in-prod-config" || r.name === "bootstrap-healthcheck" || r.name === "config-validation" || r.name === "timezone-pin") return "runtime";
      if (r.name.includes("test") || r.name === "e2e-quality") return "testing";
      if (r.name.includes("controller") || r.name.includes("deprecated-api") || r.name === "pagination" || r.name === "rate-limiting" || r.name === "no-generic-response-keys" || r.name === "api-documentation" || r.name === "auth") return "api";
      if (r.name.includes("dto")) return "semantics";
      if (r.name === "database-queries" || r.name === "domain-service" || r.name === "aggregate-id" || r.name === "logging") return "architecture";
      return null;
    })();
    if (bucket) {
      breakdown[bucket] += r.score;
      breakdownMax[bucket] += r.maxScore;
    }
  }
  const total = rawMax > 0 ? Math.round(rawScore / rawMax * 100) : 0;
  return { total, rawScore, rawMax, breakdown, breakdownMax, failures, skippedEvaluators };
}

// evaluators/shared/profile.ts
var fs36 = __toESM(require("node:fs"));
var path45 = __toESM(require("node:path"));
var PROFILES = ["benchmark", "adopt"];
var LAYOUT_BOUND = ["structure", "cqrs-pattern"];
var HOUSE_NAMING = ["file-naming"];
var LAYERS = ["domain", "application", "interface", "infrastructure"];
function usesPlaybookLayout(root) {
  const src = path45.join(root, "src");
  if (!fs36.existsSync(src)) return false;
  return fs36.readdirSync(src, { withFileTypes: true }).filter((entry) => entry.isDirectory()).some((entry) => LAYERS.some((layer) => fs36.existsSync(path45.join(src, entry.name, layer))));
}
function notApplicableFor(profile, root) {
  if (profile === "benchmark") return [];
  const out = HOUSE_NAMING.map((evaluator) => ({
    evaluator,
    reason: "playbook house naming (<name>-module.ts, no *.service.ts) \u2014 not a NestJS convention"
  }));
  if (!usesPlaybookLayout(root)) {
    for (const evaluator of LAYOUT_BOUND) {
      out.push({
        evaluator,
        reason: "project does not use the playbook layout src/<context>/{domain,application,interface,infrastructure}"
      });
    }
  }
  if (!fs36.existsSync(path45.join(root, "node_modules", ".bin", "tsc"))) {
    out.push({ evaluator: "build", reason: "dependencies not installed (node_modules/.bin/tsc missing)" });
  }
  return out;
}

// evaluators/cli/run.ts
var EVALUATORS = {
  structure: evaluateStructure,
  "file-naming": evaluateFileNaming,
  "layer-dependency": evaluateLayerDependency,
  "repository-pattern": evaluateRepositoryPattern,
  "repository-naming": evaluateRepositoryNaming,
  "controller-path": evaluateControllerPath,
  checklist: evaluateChecklist,
  "cqrs-pattern": evaluateCqrsPattern,
  "error-handling": evaluateErrorHandling,
  "test-presence": evaluateTestPresence,
  "dto-validation": evaluateDtoValidation,
  "task-queue": evaluateTaskQueue,
  scheduler: evaluateScheduler,
  "deprecated-api": evaluateDeprecatedApi,
  "module-di-ast": evaluateModuleDI,
  "import-graph": evaluateImportGraph,
  "domain-event-outbox": evaluateDomainEventOutbox,
  build: evaluateBuild,
  "test-run": evaluateTestRun,
  "secret-manager": evaluateSecretManager,
  "config-validation": evaluateConfigValidation,
  logging: evaluateLogging,
  auth: evaluateAuth,
  "bootstrap-healthcheck": evaluateBootstrapHealthcheck,
  "e2e-quality": evaluateE2eQuality,
  dockerfile: evaluateDockerfile,
  "local-dev": evaluateLocalDev,
  "rate-limiting": evaluateRateLimiting,
  pagination: evaluatePagination,
  "database-queries": evaluateDatabaseQueries,
  "domain-service": evaluateDomainService,
  "aggregate-id": evaluateAggregateId,
  "domain-layer-isolation": evaluateDomainLayerIsolation,
  "interface-no-infrastructure": evaluateInterfaceNoInfrastructure,
  "aggregate-no-public-setters": evaluateAggregateNoPublicSetters,
  "no-cross-aggregate-reference": evaluateNoCrossAggregateReference,
  "no-cross-bc-repository-in-application": evaluateNoCrossBcRepositoryInApplication,
  "soft-delete-filter": evaluateSoftDeleteFilter,
  "no-generic-response-keys": evaluateNoGenericResponseKeys,
  "query-handler-no-raw-aggregate": evaluateQueryHandlerNoRawAggregate,
  "no-cross-bc-domain-import": evaluateNoCrossBcDomainImport,
  "no-orm-autosync-in-prod-config": evaluateNoOrmAutosyncInProdConfig,
  "api-documentation": evaluateApiDocumentation,
  "user-context-store": evaluateUserContextStore,
  "timezone-pin": evaluateTimezonePin
};
function parseArgs(argv) {
  let projectRoot = null;
  let only = null;
  let out = null;
  let profile = "benchmark";
  let docBase = null;
  for (const arg of argv) {
    if (arg.startsWith("--only=")) {
      only = arg.slice("--only=".length).split(",").map((s) => s.trim()).filter(Boolean);
    } else if (arg.startsWith("--out=")) {
      out = arg.slice("--out=".length);
    } else if (arg.startsWith("--doc-base=")) {
      docBase = arg.slice("--doc-base=".length);
    } else if (arg.startsWith("--profile=")) {
      const value = arg.slice("--profile=".length);
      if (!PROFILES.includes(value)) {
        console.error(`Unknown profile: ${value} (available: ${PROFILES.join(", ")})`);
        process.exit(1);
      }
      profile = value;
    } else if (!arg.startsWith("--")) {
      projectRoot = arg;
    }
  }
  if (!projectRoot) {
    console.error("Usage: npm run evaluate -- <projectRoot> [--only=a,b,c] [--out=report.json] [--profile=benchmark|adopt] [--doc-base=<url>]");
    process.exit(1);
  }
  return { projectRoot, only, out, profile, docBase };
}
function gradeFor(total) {
  if (total >= 90) return "A";
  if (total >= 80) return "B";
  if (total >= 70) return "C";
  if (total >= 60) return "D";
  return "F";
}
function main() {
  const { projectRoot, only, out, profile, docBase } = parseArgs(process.argv.slice(2));
  const absRoot = path46.resolve(projectRoot);
  if (!fs37.existsSync(absRoot)) {
    console.error(`projectRoot not found: ${absRoot}`);
    process.exit(1);
  }
  const names = only ?? Object.keys(EVALUATORS);
  const unknown = names.filter((name) => !(name in EVALUATORS));
  if (unknown.length > 0) {
    console.error(`Unknown evaluator(s): ${unknown.join(", ")}`);
    console.error(`Available: ${Object.keys(EVALUATORS).join(", ")}`);
    process.exit(1);
  }
  const notApplicable = notApplicableFor(profile, absRoot).filter((entry) => names.includes(entry.evaluator));
  const skipped = new Set(notApplicable.map((entry) => entry.evaluator));
  const results = names.map(
    (name) => skipped.has(name) ? { name, score: 0, maxScore: 0, failures: [] } : EVALUATORS[name](absRoot)
  );
  const report = aggregate(results);
  const output = {
    projectRoot: absRoot,
    profile,
    totalScore: report.total,
    grade: gradeFor(report.total),
    rawScore: report.rawScore,
    rawMax: report.rawMax,
    runEvaluators: names,
    skippedEvaluators: report.skippedEvaluators,
    notApplicable,
    // docRef is relative to implementations/nestjs/; outside this repo it only resolves as a URL
    failures: docBase ? report.failures.map((f) => f.docRef ? { ...f, docRef: new URL(f.docRef, docBase).href } : f) : report.failures
  };
  const json = JSON.stringify(output, null, 2);
  if (out) {
    fs37.writeFileSync(out, json);
    console.log(`Report written to ${out}`);
  } else {
    console.log(json);
  }
  console.log(
    `
${output.grade} (${output.totalScore}/100, raw ${output.rawScore}/${output.rawMax}) \u2014 ${output.failures.length} failure(s) across ${names.length} evaluator(s), ${output.skippedEvaluators.length} skipped (not applicable)`
  );
  const blocking = report.failures.filter((f) => f.severity !== "low");
  if (blocking.length > 0) {
    process.exit(1);
  }
}
main();
