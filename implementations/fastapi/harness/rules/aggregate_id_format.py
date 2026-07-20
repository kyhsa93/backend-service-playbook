"""[22] aggregate-id-format: `generate_id()`가 하이픈 없는 32자리 hex(`.hex`)를 사용하는지
(aggregate-id.md)

aggregate-id.md는 Aggregate ID를 "UUID v4에서 하이픈을 제거한 32자리 hex 문자열"로
못박는다 — `uuid.uuid4().hex`가 올바른 방식이고, `str(uuid.uuid4())`(하이픈 포함 36자리)는
잘못된 방식이다. 이 저장소의 공통 ID 발급 유틸(`src/common/generate_id.py`)
단일 파일만 검사한다 — 넓은 스캔이 아니라, ID 형식이 실제로 정의되는 지점 하나만 확인한다.
"""

from __future__ import annotations

import re

from .common import RuleResult, failed, passed, read, rel, skipped

CANDIDATE_PATHS = ("src/common/generate_id.py",)

HYPHENATED_UUID = re.compile(r"\bstr\s*\(\s*uuid\.?u{0,1}uid4\s*\(\s*\)\s*\)")
HEX_UUID = re.compile(r"uuid4?\s*\(\s*\)\s*\.\s*hex\b")


def _find_generate_id_file(root: str) -> str | None:
    import os

    for candidate in CANDIDATE_PATHS:
        path = os.path.join(root, candidate)
        if os.path.isfile(path):
            return path
    return None


def check(root: str, py_files: list[str]) -> RuleResult:
    del py_files  # 단일 파일 검사 — 스캔 대상은 root 기준 고정 경로 하나
    result = RuleResult("aggregate-id-format")

    path = _find_generate_id_file(root)
    if path is None:
        result.add(skipped("src/common/generate_id.py 없음"))
        return result

    label = rel(root, path)
    src = read(path)

    if "def generate_id" not in src:
        result.add(failed(label, "generate_id() 함수가 정의되어 있지 않음(aggregate-id.md)"))
        return result

    if HYPHENATED_UUID.search(src):
        result.add(
            failed(
                label,
                "str(uuid.uuid4())는 하이픈이 포함된 36자리 문자열을 만듦 — 하이픈 없는 32자리"
                " hex(`uuid.uuid4().hex`)를 사용해야 함(aggregate-id.md)",
            )
        )
        return result

    if HEX_UUID.search(src):
        result.add(passed(f"{label} (uuid4().hex — 하이픈 없는 32자리)"))
    else:
        result.add(
            failed(
                label,
                "generate_id()가 uuid4().hex를 사용하는지 확인할 수 없음 — 하이픈 없는 32자리"
                " hex 형식이어야 함(aggregate-id.md)",
            )
        )
    return result
