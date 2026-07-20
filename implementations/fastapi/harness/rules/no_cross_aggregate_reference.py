"""[16] no-cross-aggregate-reference: 같은 BC 안에서 한 Aggregate가 다른 Aggregate를 직접 참조 금지
(domain-service.md)

Payment BC는 `Payment`/`Refund` 두 Aggregate를 갖는 이 저장소의 유일한 예시다
(layer-architecture.md "Domain Service" 절 참고) — `Refund`는 원 결제를 `payment_id: str`
참조로만 알고, `Payment`는 자신에 대한 환불 시도를 전혀 모른다. 두 Aggregate 중 하나가
상대 Aggregate 클래스를 필드/생성자 파라미터 타입으로 직접 갖게 되면 Aggregate 경계가
무너진다 — 여러 Aggregate를 조율하는 판단은 Domain Service(`RefundEligibilityService`)의
몫이어야 한다.

범위를 `src/payment/domain/{payment.py,refund.py}`로 고정한 이유: 이 저장소에서 실제로 두
Aggregate가 공존하는 유일한 BC이기 때문이다(다른 BC는 단일 Aggregate). 새 다중 Aggregate
BC가 생기면 이 규칙의 대상 쌍을 함께 확장해야 한다 — 일반화된 "모든 도메인에서 자동으로
Aggregate 쌍을 추론"하는 방식은 어떤 클래스가 Aggregate Root인지(Entity/Value Object와
구분)를 AST만으로 안정적으로 판별할 수 없어 오탐 위험이 크므로 선택하지 않았다.
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
            result.add(failed(target_rel, f"파일을 파싱할 수 없음: {e}"))
            continue

        if _referenced_types(tree, own_class, forbidden_class):
            result.add(
                failed(
                    target_rel,
                    f"{own_class} Aggregate가 {forbidden_class} 클래스를 필드/생성자 파라미터 타입으로 직접"
                    f" 참조함 — {forbidden_class}는 ID(예: payment_id: str)로만 참조해야 하고, 두 Aggregate를"
                    " 조율하는 판단은 Domain Service(RefundEligibilityService)에 위치해야 함(domain-service.md)",
                )
            )
        else:
            result.add(passed(f"{target_rel} (no-cross-aggregate-reference)"))

    if not found:
        result.add(skipped("src/payment/domain/{payment.py,refund.py} 없음"))
    return result
