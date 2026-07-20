"""[21] dockerfile-conventions: Dockerfile 멀티스테이지·HEALTHCHECK·.dockerignore (container.md)

이 규칙은 다른 규칙들과 달리 `py_files`를 순회하지 않는다 — `<root>/Dockerfile`,
`<root>/.dockerignore`를 텍스트로 직접 읽어 확인한다. container.md의 "멀티스테이지 빌드로
최종 이미지 크기를 줄인다", "HEALTHCHECK로 컨테이너 자체 상태를 노출한다" 원칙과, 불필요한
파일(`.git/`, `.venv/`, 테스트 등)이 빌드 컨텍스트에 포함되지 않도록 하는 `.dockerignore`
관례를 검사한다.

검사 항목:
(a) `FROM` 라인이 2개 이상 — 멀티스테이지 빌드
(b) `HEALTHCHECK` 인스트럭션 존재
(c) `.dockerignore`가 Dockerfile과 같은 디렉토리에 존재하고, 최소한의 제외 패턴
    (`.git`, 가상환경 디렉토리, 테스트 디렉토리 중 하나 이상)을 포함
"""

from __future__ import annotations

import os
import re

from .common import RuleResult, failed, passed, skipped

FROM_RE = re.compile(r"^\s*FROM\s+\S+", re.MULTILINE)
HEALTHCHECK_RE = re.compile(r"^\s*HEALTHCHECK\b", re.MULTILINE)
REASONABLE_EXCLUDES = (".git", ".venv", "venv", "__pycache__", "tests", "test")


def _find_dockerfile(root: str) -> str | None:
    for candidate in ("Dockerfile", os.path.join("examples", "Dockerfile")):
        path = os.path.join(root, candidate)
        if os.path.isfile(path):
            return path
    return None


def check(root: str, py_files: list[str]) -> RuleResult:
    del py_files  # 시그니처 통일용 — 이 규칙은 Dockerfile/.dockerignore를 직접 읽으므로 미사용
    result = RuleResult("dockerfile-conventions")

    dockerfile_path = _find_dockerfile(root)
    if dockerfile_path is None:
        result.add(skipped("Dockerfile 없음"))
        return result

    dockerfile_dir = os.path.dirname(dockerfile_path)
    label = os.path.relpath(dockerfile_path, root)

    try:
        with open(dockerfile_path, encoding="utf-8") as fh:
            content = fh.read()
    except OSError as e:
        result.add(failed(label, f"Dockerfile을 읽을 수 없음: {e}"))
        return result

    from_count = len(FROM_RE.findall(content))
    if from_count >= 2:
        result.add(passed(f"{label} (멀티스테이지 빌드, FROM {from_count}개)"))
    else:
        result.add(failed(label, f"멀티스테이지 빌드가 아님 — FROM {from_count}개(2개 이상 필요, container.md)"))

    if HEALTHCHECK_RE.search(content):
        result.add(passed(f"{label} (HEALTHCHECK 존재)"))
    else:
        result.add(failed(label, "HEALTHCHECK 인스트럭션이 없음(container.md)"))

    dockerignore_path = os.path.join(dockerfile_dir, ".dockerignore")
    dockerignore_label = os.path.relpath(dockerignore_path, root)
    if not os.path.isfile(dockerignore_path):
        result.add(failed(dockerignore_label, ".dockerignore 파일이 없음"))
        return result

    try:
        with open(dockerignore_path, encoding="utf-8") as fh:
            ignore_content = fh.read()
    except OSError as e:
        result.add(failed(dockerignore_label, f".dockerignore를 읽을 수 없음: {e}"))
        return result

    if any(pattern in ignore_content for pattern in REASONABLE_EXCLUDES):
        result.add(passed(f"{dockerignore_label} (합리적인 제외 패턴 확인)"))
    else:
        result.add(
            failed(
                dockerignore_label,
                ".dockerignore에 .git/venv/tests 등 합리적인 제외 패턴이 없음",
            )
        )

    return result
