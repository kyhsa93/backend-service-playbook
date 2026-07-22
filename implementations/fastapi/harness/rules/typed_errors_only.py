"""[25] typed-errors-only: raising a generic exception with a free-form string is
forbidden in domain/·application/ (AGENTS.md "type errors as an enum — a free-form string
is forbidden")

This repository's established convention is to define a typed exception class (e.g.
`AccountNotFoundError(AccountError)`) per domain in `domain/errors.py`, mapped 1:1 with an
Enum in `domain/error_codes.py` (error-handling.md). Throwing a Python built-in generic
exception with an ad-hoc string message, like `raise Exception("...")`/`raise
ValueError("...")`, is an anti-pattern that bypasses this convention — the caller can't
distinguish the kind of error without string matching.

Raising an already-typed custom exception class this repository defines, like `raise
SomeTypedError(...) from e`, isn't targeted — only a case that raises a built-in generic
exception name itself is caught. A `raise NotImplementedError` with no argument (an idiom
such as an ABC stub) is excluded, since it has no "free-form string" — only a call that
actually takes a string/f-string argument is treated as a violation.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped

GENERIC_EXCEPTIONS = {
    "Exception",
    "BaseException",
    "ValueError",
    "RuntimeError",
    "TypeError",
    "KeyError",
    "AttributeError",
    "OSError",
    "IOError",
    "AssertionError",
    "LookupError",
    "IndexError",
    "NotImplementedError",
    "StopIteration",
}


def _has_string_arg(call: ast.Call) -> bool:
    def is_stringy(node: ast.expr) -> bool:
        return (isinstance(node, ast.Constant) and isinstance(node.value, str)) or isinstance(node, ast.JoinedStr)

    return any(is_stringy(a) for a in call.args) or any(is_stringy(kw.value) for kw in call.keywords)


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("typed-errors-only")
    found = False

    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn and "/application/" not in fn:
            continue
        found = True
        src = read(f)
        r = rel(root, f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        violations = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.Raise) or node.exc is None:
                continue
            exc = node.exc
            if not isinstance(exc, ast.Call) or not isinstance(exc.func, ast.Name):
                continue
            if exc.func.id in GENERIC_EXCEPTIONS and _has_string_arg(exc):
                violations.append((node.lineno, exc.func.id))

        if violations:
            detail = ", ".join(f"line {ln}: raise {name}(...)" for ln, name in violations)
            result.add(
                failed(
                    r,
                    f"{detail} — a typed exception class from domain/errors.py must be"
                    " raised instead of a built-in generic exception with a free-form"
                    " string (AGENTS.md, error-handling.md)",
                )
            )
        else:
            result.add(passed(f"{r} (typed-errors-only)"))

    if not found:
        result.add(skipped("No Python file in domain/·application/"))
    return result
