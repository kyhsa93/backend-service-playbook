"""[15] aggregate-no-public-setters: domain/ 클래스에 쓰기용 `@x.setter` 프로퍼티 금지 (tactical-ddd.md)

이 저장소의 Aggregate 컨벤션(`examples/src/account/domain/account.py`,
`examples/src/payment/domain/payment.py` 등)은 상태 변경을 항상 이름이 있는 도메인
메서드(`deposit()`, `suspend()`, `complete()`)를 통해서만 허용하고, 필드는 일반 속성
할당(`self.status = ...`)으로 클래스 **내부**에서만 대입한다 — `@property`+`@x.setter` 쌍으로
외부에서 직접 대입 가능한 쓰기용 프로퍼티를 두지 않는다.

**범위를 좁힌 이유**: Python에는 진짜 접근 제어가 없다 — "application/에서 외부 코드가
Aggregate의 public 속성을 직접 재대입하는지"를 안정적으로 잡으려면 어떤 변수가 실제로
Aggregate 인스턴스인지 타입 추론이 필요한데, 이는 AST만으로는 신뢰도가 낮고(변수명이
`account`라고 항상 Account 인스턴스라는 보장이 없음) 오탐 위험이 크다. 그래서 이 규칙은
모호함이 없는 부분만 검사한다 — `@<name>.setter` 데코레이터는 Python 문법상 명확하게
식별 가능하고, 이 저장소의 실제 Aggregate 어디에도 쓰이지 않는 패턴이므로 정밀하게 잡을 수
있다. "application/에서의 외부 직접 대입" 검사는 오탐 위험 때문에 이번 라운드에서는
구현하지 않는다.
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
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
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
                    "domain/ 클래스에 쓰기용 @x.setter 프로퍼티가 있음 — 상태 변경은 이름이 있는 도메인"
                    " 메서드(deposit(), suspend() 등)를 통해서만 해야 함(tactical-ddd.md): " + ", ".join(violations),
                )
            )
        else:
            result.add(passed(f"{r} (aggregate-no-public-setters)"))
    if not found:
        result.add(skipped("domain/ 에 @x.setter 프로퍼티 없음"))
    return result
