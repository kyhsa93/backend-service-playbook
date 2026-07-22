"""[14] domain-layer-isolation: the domain/ layer must not import across layers or domains
(layer-architecture.md)

Unlike domain-purity (rules/domain_purity.py), which checks domain/'s purity via a
blocklist of framework/library names (fastapi/sqlalchemy/aioboto3), this rule is a **path-
based structural check** — a domain/ file (of the same domain or another) must not import
any application/, infrastructure/, or interface/ package. It enforces via AST the
layer-architecture.md dependency-direction principle that the Domain layer depends on no
layer at all.

The blocklist approach: it catches a violation only when a segment of the imported
module's path (each piece split on dots) exactly matches "application", "infrastructure",
or "interface" — both a relative import (`from ..application.x import Y` →
module="application.x") and an absolute/cross-domain import (`from ...card.infrastructure.x
import Y` → module="card.infrastructure.x") are caught identically this way. Only exact
segment matching is used to avoid a false positive against an unrelated third-party module
whose name happens to contain "interface" etc. (none exist in this repository's dependency
list, for what it's worth).
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN_SEGMENTS = {"application", "infrastructure", "interface"}


def _module_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [node.module] if node.module else []
    if isinstance(node, ast.Import):
        return [alias.name for alias in node.names]
    return []


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("domain-layer-isolation")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn:
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
                hit = FORBIDDEN_SEGMENTS.intersection(segments)
                if hit:
                    violations.append(f"{module} ({'/'.join(sorted(hit))})")

        if violations:
            result.add(
                failed(
                    r,
                    "domain/ must not import application/·infrastructure/·interface/ (of the"
                    " same domain or another) — the domain layer must not depend on any layer"
                    " (layer-architecture.md): " + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (domain-layer-isolation)"))
    if not found:
        result.add(skipped("No Python file in domain/"))
    return result
