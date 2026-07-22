"""[9] layer-dependency: application/ must not import infrastructure/

Dependency inversion — application must depend only on domain/'s abstract interfaces
(ABC/Technical Service interfaces), and must never directly import a concrete
implementation from infrastructure/. See the Technical Service pattern in
docs/architecture/domain-service.md and the dependency-direction rule in
layer-architecture.md.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("layer-dependency")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/application/" not in fn:
            continue
        found = True
        r = rel(root, f)
        src = read(f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"Failed to parse the file: {e}"))
            continue

        violations = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.ImportFrom):
                continue
            module = node.module or ""
            if "infrastructure" in module.split("."):
                names = ", ".join(alias.name for alias in node.names)
                violations.append(f"imports {names} from {'.' * node.level}{module}")

        if violations:
            result.add(
                failed(
                    r,
                    "application/ must not import an infrastructure/ implementation directly "
                    "(a dependency-inversion violation — it must depend on a domain/ interface): "
                    + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (layer-dependency)"))

    if not found:
        result.add(skipped("No Python file in application/"))
    return result
