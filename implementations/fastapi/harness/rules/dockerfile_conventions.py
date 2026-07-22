"""[21] dockerfile-conventions: Dockerfile multi-stage·HEALTHCHECK·.dockerignore (container.md)

Unlike the other rules, this one doesn't iterate over `py_files` — it reads
`<root>/Dockerfile` and `<root>/.dockerignore` directly as text. It checks container.md's
principles of "reduce the final image size via a multi-stage build" and "expose the
container's own health status via HEALTHCHECK," plus the `.dockerignore` convention that
keeps unnecessary files (`.git/`, `.venv/`, tests, etc.) out of the build context.

Checked items:
(a) 2 or more `FROM` lines — a multi-stage build
(b) A `HEALTHCHECK` instruction is present
(c) A `USER` instruction is present — runs as non-root
(d) `.dockerignore` exists in the same directory as the Dockerfile, and includes at least
    one minimal exclusion pattern (`.git`, a virtualenv directory, a test directory)
"""

from __future__ import annotations

import os
import re

from .common import RuleResult, failed, passed, skipped

FROM_RE = re.compile(r"^\s*FROM\s+\S+", re.MULTILINE)
HEALTHCHECK_RE = re.compile(r"^\s*HEALTHCHECK\b", re.MULTILINE)
USER_RE = re.compile(r"^\s*USER\s+\S+", re.MULTILINE)
REASONABLE_EXCLUDES = (".git", ".venv", "venv", "__pycache__", "tests", "test")


def _find_dockerfile(root: str) -> str | None:
    for candidate in ("Dockerfile", os.path.join("examples", "Dockerfile")):
        path = os.path.join(root, candidate)
        if os.path.isfile(path):
            return path
    return None


def check(root: str, py_files: list[str]) -> RuleResult:
    del py_files  # for signature uniformity — unused, since this rule reads Dockerfile/.dockerignore directly
    result = RuleResult("dockerfile-conventions")

    dockerfile_path = _find_dockerfile(root)
    if dockerfile_path is None:
        result.add(skipped("No Dockerfile"))
        return result

    dockerfile_dir = os.path.dirname(dockerfile_path)
    label = os.path.relpath(dockerfile_path, root)

    try:
        with open(dockerfile_path, encoding="utf-8") as fh:
            content = fh.read()
    except OSError as e:
        result.add(failed(label, f"Cannot read the Dockerfile: {e}"))
        return result

    from_count = len(FROM_RE.findall(content))
    if from_count >= 2:
        result.add(passed(f"{label} (a multi-stage build, {from_count} FROM lines)"))
    else:
        result.add(failed(label, f"Not a multi-stage build — {from_count} FROM line(s) (2+ required, container.md)"))

    if HEALTHCHECK_RE.search(content):
        result.add(passed(f"{label} (HEALTHCHECK present)"))
    else:
        result.add(failed(label, "No HEALTHCHECK instruction (container.md)"))

    if USER_RE.search(content):
        result.add(passed(f"{label} (a non-root USER is present)"))
    else:
        result.add(failed(label, "No USER instruction — the container runs as root (container.md)"))

    dockerignore_path = os.path.join(dockerfile_dir, ".dockerignore")
    dockerignore_label = os.path.relpath(dockerignore_path, root)
    if not os.path.isfile(dockerignore_path):
        result.add(failed(dockerignore_label, "No .dockerignore file"))
        return result

    try:
        with open(dockerignore_path, encoding="utf-8") as fh:
            ignore_content = fh.read()
    except OSError as e:
        result.add(failed(dockerignore_label, f"Cannot read .dockerignore: {e}"))
        return result

    if any(pattern in ignore_content for pattern in REASONABLE_EXCLUDES):
        result.add(passed(f"{dockerignore_label} (confirmed a reasonable exclusion pattern)"))
    else:
        result.add(
            failed(
                dockerignore_label,
                ".dockerignore has no reasonable exclusion pattern such as .git/venv/tests",
            )
        )

    return result
