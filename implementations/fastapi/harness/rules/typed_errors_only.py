"""[25] typed-errors-only: domain/·application/ 에서 free-form 문자열을 든 제네릭 예외 raise 금지
(AGENTS.md "에러는 enum으로 타입화 — free-form 문자열 금지")

이 저장소의 확립된 관례는 도메인별 `domain/errors.py`에 타입화된 예외 클래스(예:
`AccountNotFoundError(AccountError)`)를 정의하고 `domain/error_codes.py`의 Enum과
1:1로 매핑하는 것이다(error-handling.md). `raise Exception("...")`/`raise ValueError("...")`
처럼 파이썬 내장 제네릭 예외에 즉석 문자열 메시지를 실어 던지는 것은 이 관례를 우회하는
안티패턴이다 — 호출자가 문자열 매칭 없이는 에러 종류를 구분할 수 없다.

`raise SomeTypedError(...) from e`처럼 이미 타입화된(이 저장소가 정의한) 커스텀 예외
클래스를 raise하는 것은 대상이 아니다 — 내장 제네릭 예외 이름 자체를 raise하는 경우만 잡는다.
인자 없는 `raise NotImplementedError`(ABC 스텁 등 관용구)는 "free-form 문자열"이 없으므로
대상에서 제외한다 — 문자열/f-string 인자를 실제로 받는 호출만 위반으로 본다.
"""

from __future__ import annotations

import ast

from .common import RuleResult, failed, norm, passed, read, rel, skipped

GENERIC_EXCEPTIONS = {
    "Exception",
    "BaseException",
    "ValueError",
    "RuntimeError",
    "TypeError",
    "KeyError",
    "AttributeError",
    "OSError",
    "IOError",
    "AssertionError",
    "LookupError",
    "IndexError",
    "NotImplementedError",
    "StopIteration",
}


def _has_string_arg(call: ast.Call) -> bool:
    def is_stringy(node: ast.expr) -> bool:
        return (isinstance(node, ast.Constant) and isinstance(node.value, str)) or isinstance(node, ast.JoinedStr)

    return any(is_stringy(a) for a in call.args) or any(is_stringy(kw.value) for kw in call.keywords)


def check(root: str, py_files: list[str]) -> RuleResult:
    result = RuleResult("typed-errors-only")
    found = False

    for f in py_files:
        fn = norm(f)
        if "/domain/" not in fn and "/application/" not in fn:
            continue
        found = True
        src = read(f)
        r = rel(root, f)

        try:
            tree = ast.parse(src, filename=f)
        except SyntaxError as e:
            result.add(failed(r, f"파일을 파싱할 수 없음: {e}"))
            continue

        violations = []
        for node in ast.walk(tree):
            if not isinstance(node, ast.Raise) or node.exc is None:
                continue
            exc = node.exc
            if not isinstance(exc, ast.Call) or not isinstance(exc.func, ast.Name):
                continue
            if exc.func.id in GENERIC_EXCEPTIONS and _has_string_arg(exc):
                violations.append((node.lineno, exc.func.id))

        if violations:
            detail = ", ".join(f"line {ln}: raise {name}(...)" for ln, name in violations)
            result.add(
                failed(
                    r,
                    f"{detail} — free-form 문자열을 든 내장 제네릭 예외 대신 domain/errors.py의"
                    " 타입화된 예외 클래스를 raise해야 함(AGENTS.md, error-handling.md)",
                )
            )
        else:
            result.add(passed(f"{r} (typed-errors-only)"))

    if not found:
        result.add(skipped("domain/·application/ Python 파일 없음"))
    return result
