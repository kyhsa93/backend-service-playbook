"""[16] no-cross-aggregate-reference: within the same BC, one Aggregate must not directly
reference another Aggregate (domain-service.md)

The Payment BC is this repository's only example with two Aggregates, `Payment`/`Refund`
(see the "Domain Service" section of layer-architecture.md) — `Refund` knows the original
payment only via a `payment_id: str` reference, and `Payment` knows nothing at all about a
refund attempt against it. If either Aggregate directly holds the other Aggregate's class
as a field/constructor-parameter type, the Aggregate boundary breaks — the decision that
coordinates multiple Aggregates must belong to a Domain Service
(`RefundEligibilityService`).

Why the scope is fixed to `src/payment/domain/{payment.py,refund.py}`: because this is
the only BC in this repository where two Aggregates actually coexist (every other BC has a
single Aggregate). If a new multi-Aggregate BC is added, this rule's target pairs must be
extended together — a generalized approach of "automatically inferring Aggregate pairs in
every domain" wasn't chosen, since AST alone can't reliably determine which class is an
Aggregate Root (as distinct from an Entity/Value Object), carrying a high false-positive
risk.
"""

from __future__ import annotations

import ast
import os

from .common import RuleResult, failed, passed, read, skipped

PAYMENT_REL = os.path.join("src", "payment", "domain", "payment.py")
REFUND_REL = os.path.join("src", "payment", "domain", "refund.py")

PAIRS = [
    (PAYMENT_REL, "Payment", "Refund"),
    (REFUND_REL, "Refund", "Payment"),
]


def _annotation_names(annotation: ast.expr | None) -> set[str]:
    if annotation is None:
        return set()
    names = set()
    for node in ast.walk(annotation):
        if isinstance(node, ast.Name):
            names.add(node.id)
    return names


def _referenced_types(tree: ast.AST, own_class: str, forbidden_class: str) -> bool:
    for node in ast.walk(tree):
        if not isinstance(node, ast.ClassDef) or node.name != own_class:
            continue
        for item in node.body:
            if isinstance(item, ast.FunctionDef | ast.AsyncFunctionDef) and item.name == "__init__":
                for arg in item.args.args + item.args.kwonlyargs:
                    if forbidden_class in _annotation_names(arg.annotation):
                        return True
            if isinstance(item, ast.AnnAssign) and forbidden_class in _annotation_names(item.annotation):
                return True
    return False


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-cross-aggregate-reference")
    found = False
    file_lookup = {os.path.relpath(f, root): f for f in py_files}

    for target_rel, own_class, forbidden_class in PAIRS:
        actual_path = file_lookup.get(target_rel)
        if actual_path is None:
            continue
        found = True
        src = read(actual_path)
        try:
            tree = ast.parse(src, filename=actual_path)
        except SyntaxError as e:
            result.add(failed(target_rel, f"Failed to parse the file: {e}"))
            continue

        if _referenced_types(tree, own_class, forbidden_class):
            result.add(
                failed(
                    target_rel,
                    f"The {own_class} Aggregate directly references the {forbidden_class} class as a"
                    f" field/constructor-parameter type — {forbidden_class} must be referenced only by"
                    " ID (e.g. payment_id: str), and the decision that coordinates the two Aggregates must"
                    " live in a Domain Service (RefundEligibilityService) (domain-service.md)",
                )
            )
        else:
            result.add(passed(f"{target_rel} (no-cross-aggregate-reference)"))

    if not found:
        result.add(skipped("src/payment/domain/{payment.py,refund.py} not found"))
    return result
