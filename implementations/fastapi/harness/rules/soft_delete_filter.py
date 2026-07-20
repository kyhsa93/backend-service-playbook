"""[24] soft-delete-filter: 상태 변경 가능한 SQLAlchemy 모델은 deleted_at 컬럼을 갖고, find
계열 쿼리는 deleted_at IS NULL로 필터링하는지 (persistence.md)

persistence.md 원칙 두 가지를 기계 검증한다:
1. "모든 상태 변경 가능한 Entity에 created_at/updated_at/deleted_at을 둔다" — `updated_at`
   컬럼이 있는(=상태가 바뀌는) 모델인데 `deleted_at` 컬럼이 없으면 실패. 불변 기록
   (`TransactionModel`처럼 `updated_at` 자체가 없는 모델)은 애초에 대상이 아니다 — 문서가
   명시한 것과 동일한 기준(불변 기록은 예외)을 그대로 코드로 옮긴 것.
2. "삭제는 soft delete, 조회는 항상 deleted_at IS NULL" — 위 기준으로 `deleted_at`을
   가진 모델에 대해, 그 모델을 조회하는 `find_*` 메서드가 `<Model>.deleted_at.is_(None)`
   류의 필터를 포함하는지 검사한다. 정확한 SQLAlchemy 체이닝(`select(X).where(...)`가 여러
   줄에 걸쳐 재대입되는 이 저장소의 실제 패턴)을 AST로 완벽히 추적하기보다, `find_*` 메서드
   소스 슬라이스 안에 `select(<Model>)`과 `<Model>.deleted_at.is_(None)`이 함께 있는지를
   텍스트로 확인한다 — 이 저장소가 실제로 쓰는 구성 패턴에 맞춘 실용적 선택.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped


def _is_model_class(node: ast.ClassDef) -> bool:
    return any(isinstance(b, ast.Name) and b.id == "Base" for b in node.bases)


def _column_names(node: ast.ClassDef) -> set[str]:
    names = set()
    for stmt in node.body:
        if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
            names.add(stmt.target.id)
    return names


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("soft-delete-filter")
    found = False

    for f in py_files:
        fn = norm(f)
        if "/infrastructure/persistence/" not in fn:
            continue

        src = read(f)
        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(rel(root, f), f"파일을 파싱할 수 없음: {e}"))
            continue

        lines = src.splitlines()
        r = rel(root, f)

        model_classes = [n for n in ast.walk(tree) if isinstance(n, ast.ClassDef) and _is_model_class(n)]
        if not model_classes:
            continue
        found = True

        find_methods = [
            n
            for n in ast.walk(tree)
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name.startswith("find_")
        ]

        for model in model_classes:
            columns = _column_names(model)
            is_mutable = "updated_at" in columns
            has_deleted_at = "deleted_at" in columns
            label = f"{r}::{model.name}"

            if not is_mutable:
                # 불변 기록(예: TransactionModel) — persistence.md의 명시적 예외, 검사 대상 아님
                continue

            if not has_deleted_at:
                result.add(
                    failed(
                        label,
                        "updated_at 컬럼이 있어 상태 변경 가능한 Entity인데 deleted_at 컬럼이 없음 —"
                        " 모든 상태 변경 가능한 Entity는 created_at/updated_at/deleted_at을 가져야 함"
                        " (persistence.md)",
                    )
                )
                continue

            # deleted_at을 가진 모델을 참조하는 find_* 메서드가 필터를 포함하는지 확인
            select_pattern = f"select({model.name})"
            filter_pattern = f"{model.name}.deleted_at.is_(None)"
            referencing_methods = [
                m for m in find_methods if select_pattern in "\n".join(lines[m.lineno - 1 : (m.end_lineno or m.lineno)])
            ]

            if not referencing_methods:
                result.add(passed(f"{label} (deleted_at 컬럼 보유, 이 파일에서 조회하는 find_* 없음)"))
                continue

            all_filtered = True
            for m in referencing_methods:
                method_src = "\n".join(lines[m.lineno - 1 : (m.end_lineno or m.lineno)])
                if filter_pattern not in method_src:
                    all_filtered = False
                    result.add(
                        failed(
                            f"{r}::{m.name}",
                            f"select({model.name})을 사용하지만 {filter_pattern} 필터가 없음 —"
                            " soft-delete된 행이 조회 결과에 포함될 수 있음(persistence.md)",
                        )
                    )

            if all_filtered:
                result.add(passed(f"{label} (find_* 전부 deleted_at IS NULL 필터 포함)"))

    if not found:
        result.add(skipped("infrastructure/persistence/의 SQLAlchemy 모델(Base 상속) 없음"))
    return result
