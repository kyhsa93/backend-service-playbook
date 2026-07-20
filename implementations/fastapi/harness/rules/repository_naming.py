"""[13] Repository/Query 메서드 네이밍 (repository-pattern.md)

domain/ 레이어의 Repository/Query ABC 인터페이스는 조회는 `find_<noun>s`(단건도 `take=1`로
동일 메서드를 재사용), 저장은 `save_<noun>`, 삭제는 `delete_<noun>` 하나로 통일해야 한다.
`infrastructure/persistence/`의 구체 구현체는 대상이 아니다 — private/내부 보조 메서드를
자유롭게 가질 수 있다(repository-impl은 그 자체로 별도 규칙이 배치를 검사한다).

블록리스트 방식(좁고 정밀하게) — 아래 안티패턴에 해당하는 추상 메서드만 실패로 잡는다.
`has_transaction_with_reference` 같은 정상 메서드를 오탐하지 않도록 넓은 긍정 문법
대신 알려진 위반 패턴만 매칭한다:
- `find_by_*` 형태(이름에 "find_by" 포함) — 예: find_by_id, find_by_account_id_and_owner_id
- 정확히 `find_all`(명사 없는 bare)
- `count`로 시작하는 메서드 — 개수는 `find_<noun>s`가 반환하는 (list, count) 튜플에
  포함되어야 하며 별도 메서드로 두지 않는다
- 정확히 `save`(bare, 명사 접미사 없음) — `save_account`처럼 명사가 붙으면 통과
- 정확히 `delete`(bare, 명사 접미사 없음) — `delete_account`처럼 명사가 붙으면 통과
- `update_`로 시작하는 메서드 — 별도 update 메서드를 두지 않는다. 상태 변경은 Aggregate를
  `find_<noun>s`로 로드해 도메인 메서드로 변경한 뒤 `save_<noun>`으로 통째로 다시 저장하는
  것으로 표현한다(부분 업데이트용 별도 메서드 금지)
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped

DOC_REF = "docs/architecture/repository-pattern.md"


def _violation_reason(name: str) -> str | None:
    if "find_by" in name:
        return f"'{name}' — find_by_* 금지, 단건 조회도 find_<noun>s(..., take=1)로 통일해야 함"
    if name == "find_all":
        return "'find_all' — 명사 없는 bare 조회 금지, find_<noun>s로 표현해야 함"
    if name.startswith("count"):
        return f"'{name}' — count 전용 메서드 금지, find_<noun>s의 (list, count) 튜플 반환에 포함해야 함"
    if name == "save":
        return "'save' — bare save 금지, save_<noun>으로 명사를 명시해야 함"
    if name == "delete":
        return "'delete' — bare delete 금지, delete_<noun>으로 명사를 명시해야 함"
    if name.startswith("update_"):
        return f"'{name}' — 별도 update_* 메서드 금지, find로 로드 후 도메인 메서드로 변경해 save_<noun>으로 저장"
    return None


def _is_abstractmethod(node: ast.FunctionDef | ast.AsyncFunctionDef) -> bool:
    for d in node.decorator_list:
        if isinstance(d, ast.Name) and d.id == "abstractmethod":
            return True
        if isinstance(d, ast.Attribute) and d.attr == "abstractmethod":
            return True
    return False


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("repository-naming")
    found = False
    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn:
            continue
        src = read(f)
        if "abstractmethod" not in src:
            continue

        r = rel(root, f)
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            found = True
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
            continue

        checked_any = False
        violations: list[str] = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.ClassDef):
                continue
            if not (node.name.endswith("Repository") or node.name.endswith("Query")):
                continue
            for item in node.body:
                if not isinstance(item, ast.FunctionDef | ast.AsyncFunctionDef):
                    continue
                if not _is_abstractmethod(item):
                    continue
                checked_any = True
                reason = _violation_reason(item.name)
                if reason:
                    violations.append(f"{node.name}.{item.name}: {reason}")

        if not checked_any:
            continue
        found = True
        if violations:
            result.add(
                failed(
                    r,
                    "Repository/Query 추상 메서드명이 네이밍 규칙을 위반함 — "
                    f"find_<noun>s/save_<noun>/delete_<noun> 패턴을 따라야 함({DOC_REF}): " + "; ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (repository-naming)"))
    if not found:
        result.add(skipped("domain/ Repository·Query ABC 없음"))
    return result
