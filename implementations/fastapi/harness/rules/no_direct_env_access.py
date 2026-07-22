"""[17] no-direct-env-access-outside-config: calling os.environ/os.getenv directly is
forbidden in domain/·application/ (config.md)

Environment-variable access is encapsulated in Pydantic `BaseSettings` classes in
`config/` (`DatabaseConfig`, `JwtConfig`, `AwsConfig`), and only `infrastructure/` (and
`config/` itself) references those configuration classes or `os.environ`/`os.getenv`
directly — `domain/` and `application/` never depend on configuration directly (see the
"Configuration access pattern" section of config.md).

A regex-based blocklist: it catches only a literal use of `os.environ`, `os.getenv(` — it
doesn't trace a local-variable alias pointing to `getenv`/`environ` (there's no such
indirect-alias usage anywhere in this repository, and over-generalizing would only
increase the false-positive risk).
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, norm, passed, read, rel, skipped

FORBIDDEN = re.compile(r"\bos\.environ\b|\bos\.getenv\s*\(")


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("no-direct-env-access-outside-config")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn and "/application/" not in fn:
            continue
        found = True
        src = read(f)
        r = rel(root, f)
        if FORBIDDEN.search(src):
            result.add(
                failed(
                    r,
                    "domain/·application/ must not call os.environ/os.getenv directly — it"
                    " must be encapsulated in a BaseSettings class in config/ (config.md)",
                )
            )
        else:
            result.add(passed(f"{r} (no-direct-env-access-outside-config)"))
    if not found:
        result.add(skipped("No Python file in domain/·application/"))
    return result
