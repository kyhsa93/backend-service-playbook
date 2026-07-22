"""[20] no-silent-except: silently swallowing an exception via `except: pass` is forbidden
in application/·infrastructure/ (observability.md)

observability.md's "errors must always be logged before being propagated"·"never
swallowed" principle — `except ...: pass` (a handler body that's exactly a single `pass`)
is a textbook anti-pattern that silently discards an exception with neither logging nor
re-propagation. It catches, via AST, only the case where the handler body is exactly
`[Pass]` — if there's a logging call or a comment (a comment leaves nothing in the AST, but
if there's even one real statement such as `logger.exception(...)`), it isn't caught.
`domain/` is excluded from the start, since it only validates state transitions (raising a
domain error) rather than handling exceptions.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-silent-except")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/" not in fn and "/infrastructure/" not in fn:
            continue
        found = True
        src = read(f)
        r = rel(root, f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        violation_lines = [
            node.lineno
            for node in ast.walk(tree)
            if isinstance(node, ast.ExceptHandler) and len(node.body) == 1 and isinstance(node.body[0], ast.Pass)
        ]

        if violation_lines:
            lines = ", ".join(str(n) for n in violation_lines)
            result.add(
                failed(
                    r,
                    f"line {lines}: except ...: pass — silently swallows the exception with"
                    " no logging. It must be logged and propagated, or (if it's a retry"
                    " target) compensated for with a separate reliability mechanism such as"
                    " the Outbox (observability.md)",
                )
            )
        else:
            result.add(passed(f"{r} (no-silent-except)"))
    if not found:
        result.add(skipped("No Python file in application/·infrastructure/"))
    return result
