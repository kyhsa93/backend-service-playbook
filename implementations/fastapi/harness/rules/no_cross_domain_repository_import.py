"""[18] no-cross-bc-repository-in-application: application/ must not directly import
another domain's Repository/Query ABC (cross-domain.md, root cross-domain-communication.md)

A domain's `application/` must not directly import another domain's
`domain/repository.py` (or a per-domain Repository file such as
`payment_repository.py`/`refund_repository.py`) — a cross-domain lookup must always go
through an Adapter ABC defined in the calling side's `application/adapter/` (+ an
implementation in `infrastructure/`, calling the target domain's Query inside it) (the ACL
pattern in cross-domain.md; actual examples: `card/infrastructure/account_adapter_impl.py`,
`payment/infrastructure/{account,card}_adapter_impl.py`). Importing `domain/repository.py`
within the same domain is fine.

**Determining the domain**: the segment right before the `application` segment in the
file path is treated as "the domain this file belongs to" (e.g.
`src/payment/application/command/x.py` → `payment`).

**Determining a violation**: it finds the `domain` segment in the import's module path,
and it's a violation if a different domain's name segment precedes it (a relative import
only shows a domain name in its module string when it crosses a domain boundary — a
same-domain relative import starts with no domain name, like `domain.repository`), and if
any segment after `domain` contains "repository" (in the file name or the imported name).
Defining one's own Adapter ABC in `application/adapter/` never triggers this pattern — an
Adapter ABC never imports another domain's `domain.repository`, and instead defines its
own independent view type.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _current_domain(fn: str) -> str | None:
    parts = fn.split("/")
    if "application" not in parts:
        return None
    idx = parts.index("application")
    if idx == 0:
        return None
    return parts[idx - 1]


def _module_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [node.module] if node.module else []
    if isinstance(node, ast.Import):
        return [alias.name for alias in node.names]
    return []


def _imported_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.ImportFrom):
        return [alias.name for alias in node.names]
    return []


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-cross-bc-repository-in-application")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/" not in fn:
            continue
        own_domain = _current_domain(fn)
        if own_domain is None:
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
                    continue  # a same-domain relative import ("domain.repository") — no domain name
                other_domain = prefix[-1]
                if other_domain in {"src", own_domain}:
                    continue
                suffix = segments[idx + 1 :]
                names = _imported_names(node)
                is_repository = any("repository" in s.lower() for s in suffix) or any(
                    "repository" in n.lower() or n.endswith("Query") for n in names
                )
                if is_repository:
                    violations.append(f"{module} ({other_domain} domain)")

        if violations:
            result.add(
                failed(
                    r,
                    f"application/({own_domain}) directly imports another domain's Repository/Query ABC —"
                    " it must go through an Adapter interface in application/adapter/ (cross-domain.md): "
                    + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (no-cross-bc-repository-in-application)"))
    if not found:
        result.add(skipped("No Python file in application/"))
    return result
