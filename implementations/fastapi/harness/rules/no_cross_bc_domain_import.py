"""[28] no-cross-bc-domain-import: one Bounded Context's domain/ must not directly import
another BC's domain/ (tactical-ddd.md "another Aggregate may only be referenced by ID")

tactical-ddd.md's principle "another Aggregate may only be referenced by ID (an object
reference is forbidden)" applies not only between Aggregates within the same BC (e.g.
payment/Refund must not reference the Payment class directly — checked by the
`no-cross-aggregate-reference` rule), but equally between different BCs.
`domain-layer-isolation` (rules/domain_layer_isolation.py) only checks that domain/
doesn't import the application/·infrastructure/·interface/ layers, and
`no-cross-aggregate-reference` only checks within `payment/domain/{payment.py,refund.py}`
— there was no rule between these two that stopped `src/card/domain/*.py` from directly
importing `src/payment/domain/*` (another BC's domain package). This rule closes that gap.

**Determining the domain**: the segment right before the `domain` segment in the file path
is treated as "the BC this file belongs to" (e.g. `src/card/domain/card.py` → `card`).

**Determining a violation**: it finds the `domain` segment in the import's module path,
and it's a violation if the segment right before it is neither its own BC's name, `src`,
nor a shared-infrastructure directory (`common`/`database`/`outbox`/`task_queue`/`config`,
`common.is_shared_dir`). A relative import within the same BC
(`from .account_status import ...`, `from .errors import ...`) is never a false positive,
since the module string has no `domain` segment at all (a Python relative import never
repeats the package name within the same package). Referencing a shared util like `from
...common.generate_id import generate_id` is also never a false positive, since `common`
is treated as a shared directory.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, is_shared_dir, norm, passed, read, rel, skipped


def _own_bc(fn: str) -> str | None:
    parts = fn.split("/")
    if "domain" not in parts:
        return None
    idx = parts.index("domain")
    if idx == 0:
        return None
    return parts[idx - 1]


def _module_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [node.module] if node.module else []
    if isinstance(node, ast.Import):
        return [alias.name for alias in node.names]
    return []


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-cross-bc-domain-import")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn:
            continue
        own_bc = _own_bc(fn)
        if own_bc is None:
            continue
        found = True
        r = rel(root, f)
        src = read(f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        violations: list[str] = []
        for node in ast.walk(tree):
            if not isinstance(node, (ast.ImportFrom, ast.Import)):
                continue
            for module in _module_names(node):
                segments = module.split(".")
                if "domain" not in segments:
                    continue
                idx = segments.index("domain")
                prefix = segments[:idx]
                if not prefix:
                    continue  # a same-BC relative import ("domain.x") — no BC name
                other_bc = prefix[-1]
                if other_bc in {"src", own_bc} or is_shared_dir(other_bc):
                    continue
                violations.append(f"{module} ({other_bc} BC)")

        if violations:
            result.add(
                failed(
                    r,
                    f"domain/({own_bc}) directly imports another BC's domain/ — another Aggregate"
                    " may only be referenced by ID; an object reference is forbidden (tactical-ddd.md): "
                    + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (no-cross-bc-domain-import)"))
    if not found:
        result.add(skipped("No Python file in domain/"))
    return result
