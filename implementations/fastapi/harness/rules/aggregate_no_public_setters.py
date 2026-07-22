"""[15] aggregate-no-public-setters: a write-capable `@x.setter` property is forbidden on a
domain/ class (tactical-ddd.md)

This repository's Aggregate convention (`examples/src/account/domain/account.py`,
`examples/src/payment/domain/payment.py`, etc.) always allows a state change only through
a named domain method (`deposit()`, `suspend()`, `complete()`), and a field is assigned
only **inside** the class via a plain attribute assignment (`self.status = ...`) — it
never has a write-capable property that can be assigned directly from outside via a
`@property`+`@x.setter` pair.

**Why the scope is narrowed**: Python has no true access control — reliably catching
"whether code in application/ directly reassigns an Aggregate's public attribute" would
require type inference to determine which variable is actually an Aggregate instance,
which AST alone can't do reliably (a variable named `account` isn't guaranteed to always
be an Account instance), carrying a high false-positive risk. So this rule checks only the
part with no ambiguity — an `@<name>.setter` decorator is clearly identifiable by Python
syntax alone, and is a pattern used nowhere in this repository's actual Aggregates, so it
can be caught precisely. Checking for "direct external assignment in application/" is not
implemented in this round, due to the false-positive risk.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _is_setter(node: ast.FunctionDef | ast.AsyncFunctionDef) -> str | None:
    for d in node.decorator_list:
        if isinstance(d, ast.Attribute) and d.attr == "setter":
            return node.name
    return None


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("aggregate-no-public-setters")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn:
            continue
        src = read(f)
        r = rel(root, f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            found = True
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        checked_any = False
        violations: list[str] = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.ClassDef):
                continue
            for item in node.body:
                if not isinstance(item, ast.FunctionDef | ast.AsyncFunctionDef):
                    continue
                setter_name = _is_setter(item)
                if setter_name is None:
                    continue
                checked_any = True
                if not setter_name.startswith("_"):
                    violations.append(f"{node.name}.{setter_name}")

        if not checked_any:
            continue
        found = True
        if violations:
            result.add(
                failed(
                    r,
                    "A domain/ class has a write-capable @x.setter property — a state"
                    " change must only happen through a named domain method (deposit(),"
                    " suspend(), etc.) (tactical-ddd.md): " + ", ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (aggregate-no-public-setters)"))
    if not found:
        result.add(skipped("No @x.setter property in domain/"))
    return result
