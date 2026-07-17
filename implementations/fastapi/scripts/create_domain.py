#!/usr/bin/env python3
"""새 도메인 스캐폴딩 생성기.

docs/reference.md의 "실전 구현 템플릿"과 examples/src/account·card/의 실제 코드를 기준으로,
Aggregate(단일 status 필드, PENDING→ACTIVE/CANCELLED) + CQRS Command/Query Handler + 도메인
이벤트 1종(cancel에서 발행) + Repository(ABC/구현체) + Router + Pydantic 스키마 + Alembic
마이그레이션까지 한 번에 생성한다.

FastAPI에는 NestJS의 CommandBus/CqrsModule/@Module 같은 DI 컨테이너가 없다 — 대신 Depends
팩토리 함수가 조립 지점이고, OutboxRelay는 프로세스 전체에 단 하나만 존재하는 공유 인스턴스다
(도메인마다 전용 OutboxRelay를 두는 NestJS와 다르다). 그래서 이 생성기는 새 도메인 디렉토리를
만드는 것 외에, 이미 존재하는 세 파일 — main.py(라우터 등록), account_router.py(공유
OutboxRelay 조립 지점인 `_outbox_relay()`), migrations/env.py(Alembic이 감지할 모델 import) —
을 함께 수정해야 한다. 기본값은 수정하지 않고 붙여넣을 스니펫만 출력하며, --wire를 줘야 실제
파일을 고친다 (기존 프로젝트 파일을 임의로 고치는 걸 원치 않을 수 있어 안전한 쪽을 기본값으로
둔다).

사용법:
  python3 scripts/create_domain.py <PascalCaseDomainName> [--out <targetSrcDir>] [--wire]

예:
  python3 scripts/create_domain.py Coupon
    → ../examples/src/coupon/ 아래에 생성(스크립트 기본 대상), main.py/account_router.py/
      migrations/env.py는 건드리지 않고 붙여넣을 스니펫만 출력
  python3 scripts/create_domain.py Coupon --out /tmp/scratch-app/src --wire
    → 지정한 src/ 아래 생성 + main.py/account_router.py/migrations/env.py/migrations/versions/
      까지 자동 배선

검증: 생성 뒤 `ruff check .`, `ruff format --check .`, `bash harness.sh <projectRoot>`로 확인한다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import secrets
import shutil
import subprocess
import sys

# ---------------------------------------------------------------------------
# 이름 변환
# ---------------------------------------------------------------------------


def to_pascal_case(raw: str) -> str:
    if not raw:
        return raw
    return raw[0].upper() + raw[1:]


def to_snake_case(pascal: str) -> str:
    # 첫 글자를 제외한 대문자 앞에 '_'를 삽입한 뒤 전부 소문자로 — "LoyaltyCategory" → "loyalty_category"
    return re.sub(r"(?<!^)(?=[A-Z])", "_", pascal).lower()


def snake_to_pascal(snake: str) -> str:
    return "".join(part[:1].upper() + part[1:] for part in snake.split("_") if part)


# 아주 단순한 규칙 기반 복수형 — 불규칙 복수형(예: Category → Categories 이상의 진짜 불규칙,
# person → people 등)은 생성 후 수동으로 고쳐야 한다는 걸 실행 결과에 안내한다. snake_case
# 문자열(단어 경계가 '_'로 보존됨) 위에서 접미사만 보고 판단하므로, 여러 단어로 된 도메인명이라도
# 마지막 단어 하나만 정확히 복수화된다 — kebab-case나 PascalCase 표기 위에서 나이브하게
# 접미사를 붙이면(예: "loyalty-category" → "loyalty-categorys") 단어 경계가 깨지는 문제를
# 피하기 위해, 복수형은 snake_case에서 계산한 뒤 kebab/PascalCase로 재변환한다.
def naive_pluralize_snake(snake: str) -> str:
    if re.search(r"[sxz]$", snake) or re.search(r"[cs]h$", snake):
        return snake + "es"
    if re.search(r"[^aeiou]y$", snake):
        return snake[:-1] + "ies"
    return snake + "s"


def sorted_dotted_imports(prefix: str, entries: list[tuple[str, str]]) -> str:
    """`from {prefix}.<module> import <names>` 줄들을 모듈명 알파벳 순으로 정렬해 반환한다.

    도메인 이름에 따라 모듈명의 알파벳 순서가 달라지므로(예: "coupon" < "repository"지만
    "voucher" > "repository"), 템플릿에 순서를 하드코딩하면 특정 도메인 이름에서만 isort
    위반이 나는 잠재 버그가 생긴다 — 항상 실제로 정렬해서 만든다.
    """
    return "\n".join(f"from {prefix}.{module} import {names}" for module, names in sorted(entries))


class Names:
    def __init__(self, raw: str) -> None:
        self.Domain = to_pascal_case(raw)
        self.domain = to_snake_case(self.Domain)
        self.domain_kebab = self.domain.replace("_", "-")
        self.domains = naive_pluralize_snake(self.domain)
        self.Domains = snake_to_pascal(self.domains)
        self.domains_kebab = self.domains.replace("_", "-")
        self.DOMAIN_SCREAM = self.domain.upper()


# ---------------------------------------------------------------------------
# 파일 생성
# ---------------------------------------------------------------------------


def generate_files(n: Names) -> dict[str, str]:
    files: dict[str, str] = {}

    # ---- domain/ ----
    files[f"{n.domain}/domain/{n.domain}_status.py"] = f"""from enum import Enum


class {n.Domain}Status(str, Enum):
    PENDING = "PENDING"
    ACTIVE = "ACTIVE"
    CANCELLED = "CANCELLED"
"""

    files[f"{n.domain}/domain/error_codes.py"] = f'''from enum import Enum


class {n.Domain}ErrorCode(str, Enum):
    {n.DOMAIN_SCREAM}_NOT_FOUND = "{n.DOMAIN_SCREAM}_NOT_FOUND"
    {n.DOMAIN_SCREAM}_ALREADY_CANCELLED = "{n.DOMAIN_SCREAM}_ALREADY_CANCELLED"
'''

    files[f"{n.domain}/domain/errors.py"] = f'''from .error_codes import {n.Domain}ErrorCode


class {n.Domain}Error(Exception):
    code: {n.Domain}ErrorCode


class {n.Domain}NotFoundError({n.Domain}Error):
    code = {n.Domain}ErrorCode.{n.DOMAIN_SCREAM}_NOT_FOUND

    def __init__(self, {n.domain}_id: str) -> None:
        super().__init__(f"{n.Domain}을(를) 찾을 수 없습니다: {{{n.domain}_id}}")
        self.{n.domain}_id = {n.domain}_id


class {n.Domain}AlreadyCancelledError({n.Domain}Error):
    code = {n.Domain}ErrorCode.{n.DOMAIN_SCREAM}_ALREADY_CANCELLED

    def __init__(self) -> None:
        super().__init__("이미 취소된 {n.Domain}입니다.")
'''

    files[f"{n.domain}/domain/events.py"] = f"""from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class {n.Domain}Cancelled:
    {n.domain}_id: str
    reason: str
    cancelled_at: datetime
"""

    aggregate_local_imports = sorted_dotted_imports(
        "",
        [
            ("errors", f"{n.Domain}AlreadyCancelledError"),
            ("events", f"{n.Domain}Cancelled"),
            (f"{n.domain}_status", f"{n.Domain}Status"),
        ],
    )
    files[f"{n.domain}/domain/{n.domain}.py"] = f"""from __future__ import annotations

from datetime import datetime
from typing import Union

from ...common.generate_id import generate_id
{aggregate_local_imports}

{n.Domain}DomainEvent = Union[{n.Domain}Cancelled]


class {n.Domain}:
    def __init__(
        self,
        {n.domain}_id: str,
        owner_id: str,
        status: {n.Domain}Status,
        created_at: datetime,
    ) -> None:
        self.{n.domain}_id = {n.domain}_id
        self.owner_id = owner_id
        self.status = status
        self.created_at = created_at
        self._events: list[{n.Domain}DomainEvent] = []

    @classmethod
    def create(cls, owner_id: str) -> {n.Domain}:
        return cls(
            {n.domain}_id=generate_id(),
            owner_id=owner_id,
            status={n.Domain}Status.PENDING,
            created_at=datetime.utcnow(),
        )

    # 이벤트를 발행하지 않는 단순 상태 전이 예시 — 도메인 이벤트가 필요 없는 변경은
    # 이렇게 그냥 상태만 바꾼다.
    def activate(self) -> None:
        self.status = {n.Domain}Status.ACTIVE

    # 이벤트를 발행하는 상태 전이 예시.
    def cancel(self, reason: str) -> None:
        if self.status == {n.Domain}Status.CANCELLED:
            raise {n.Domain}AlreadyCancelledError()
        self.status = {n.Domain}Status.CANCELLED
        self._events.append(
            {n.Domain}Cancelled({n.domain}_id=self.{n.domain}_id, reason=reason, cancelled_at=datetime.utcnow())
        )

    def pull_events(self) -> list[{n.Domain}DomainEvent]:
        events, self._events = self._events, []
        return events
"""

    files[f"{n.domain}/domain/repository.py"] = f'''from abc import ABC, abstractmethod

from .{n.domain} import {n.Domain}


class {n.Domain}Query(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용. `save()` 등 쓰기 메서드를 노출하지 않는다
    (cqrs-pattern.md 참고). `{n.Domain}Repository`(쓰기 모델)와 메서드 시그니처를 공유하지만
    별도 계약이다 — Query Handler는 반드시 이 타입으로만 의존해야 한다.
    """

    @abstractmethod
    async def find_by_id(self, {n.domain}_id: str, owner_id: str) -> {n.Domain} | None: ...


class {n.Domain}Repository({n.Domain}Query, ABC):
    @abstractmethod
    async def find_all(
        self,
        page: int,
        take: int,
        {n.domain}_id: str | None = None,
        owner_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[{n.Domain}], int]: ...

    @abstractmethod
    async def save(self, {n.domain}: {n.Domain}) -> None: ...
'''

    # ---- application/command/ ----
    create_handler_local_imports = sorted_dotted_imports(
        "...domain", [(n.domain, n.Domain), ("repository", f"{n.Domain}Repository")]
    )
    files[f"{n.domain}/application/command/create_{n.domain}_handler.py"] = f"""from dataclasses import dataclass

from ....outbox.outbox_relay import OutboxRelay
{create_handler_local_imports}


@dataclass
class Create{n.Domain}Command:
    requester_id: str


class Create{n.Domain}Handler:
    def __init__(self, repo: {n.Domain}Repository, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd: Create{n.Domain}Command) -> {n.Domain}:
        {n.domain} = {n.Domain}.create(owner_id=cmd.requester_id)
        await self._repo.save({n.domain})
        await self._outbox_relay.process_pending()
        return {n.domain}
"""

    files[f"{n.domain}/application/command/cancel_{n.domain}_handler.py"] = f"""from dataclasses import dataclass

from ....outbox.outbox_relay import OutboxRelay
from ...domain.errors import {n.Domain}NotFoundError
from ...domain.repository import {n.Domain}Repository


@dataclass
class Cancel{n.Domain}Command:
    {n.domain}_id: str
    requester_id: str
    reason: str


class Cancel{n.Domain}Handler:
    def __init__(self, repo: {n.Domain}Repository, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd: Cancel{n.Domain}Command) -> None:
        {n.domain} = await self._repo.find_by_id(cmd.{n.domain}_id, cmd.requester_id)
        if {n.domain} is None:
            raise {n.Domain}NotFoundError(cmd.{n.domain}_id)
        {n.domain}.cancel(cmd.reason)
        await self._repo.save({n.domain})
        await self._outbox_relay.process_pending()
"""

    # ---- application/query/ ----
    files[f"{n.domain}/application/query/result.py"] = f"""from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass
class Get{n.Domain}Result:
    {n.domain}_id: str
    owner_id: str
    status: str
    created_at: datetime
"""

    files[f"{n.domain}/application/query/get_{n.domain}_handler.py"] = f"""from dataclasses import dataclass

from ...domain.errors import {n.Domain}NotFoundError
from ...domain.repository import {n.Domain}Query
from .result import Get{n.Domain}Result


@dataclass
class Get{n.Domain}Query:
    {n.domain}_id: str
    requester_id: str


class Get{n.Domain}Handler:
    def __init__(self, query: {n.Domain}Query) -> None:
        self._query = query

    async def execute(self, query: Get{n.Domain}Query) -> Get{n.Domain}Result:
        {n.domain} = await self._query.find_by_id(query.{n.domain}_id, query.requester_id)
        if {n.domain} is None:
            raise {n.Domain}NotFoundError(query.{n.domain}_id)
        return Get{n.Domain}Result(
            {n.domain}_id={n.domain}.{n.domain}_id,
            owner_id={n.domain}.owner_id,
            status={n.domain}.status.value,
            created_at={n.domain}.created_at,
        )
"""

    # ---- application/event/ ----
    event_handler_file = f"{n.domain}/application/event/{n.domain}_cancelled_event_handler.py"
    files[event_handler_file] = f'''from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


class {n.Domain}CancelledEventHandler:
    """취소된 {n.Domain}에 대한 후속 처리(알림, 다른 BC로의 Integration Event 발행 등)를
    여기서 구현한다. 스캐폴딩 단계에서는 로깅만 한다.
    """

    async def handle(self, payload: dict) -> None:
        logger.info("{n.Domain} 취소됨: %s_id=%s reason=%s", "{n.domain}", payload["{n.domain}_id"], payload["reason"])
'''

    # ---- infrastructure/persistence/ ----
    persistence_local_imports = sorted_dotted_imports(
        "...domain",
        [
            (n.domain, n.Domain),
            (f"{n.domain}_status", f"{n.Domain}Status"),
            ("repository", f"{n.Domain}Repository"),
        ],
    )
    files[f"{n.domain}/infrastructure/persistence/{n.domain}_repository.py"] = f'''from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from ....account.infrastructure.persistence.account_repository import Base
{persistence_local_imports}


class {n.Domain}Model(Base):
    __tablename__ = "{n.domains}"

    id: Mapped[str] = mapped_column(primary_key=True)
    owner_id: Mapped[str]
    status: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)


class SqlAlchemy{n.Domain}Repository({n.Domain}Repository):
    def __init__(self, session: AsyncSession) -> None:
        # 지연 import — outbox_model.py가 account_repository.py의 Base를 import하므로,
        # 모듈 최상단에서 OutboxWriter를 import하면 순환 참조가 발생한다
        # (module-pattern.md "Python의 순환 참조" 참조).
        from ....outbox.outbox_writer import OutboxWriter

        self._session = session
        self._outbox_writer = OutboxWriter(session)

    async def find_by_id(self, {n.domain}_id: str, owner_id: str) -> {n.Domain} | None:
        stmt = select({n.Domain}Model).where(
            {n.Domain}Model.id == {n.domain}_id,
            {n.Domain}Model.owner_id == owner_id,
            {n.Domain}Model.deleted_at.is_(None),
        )
        row = (await self._session.execute(stmt)).scalar_one_or_none()
        if row is None:
            return None
        return self._to_domain(row)

    async def find_all(
        self,
        page: int,
        take: int,
        {n.domain}_id: str | None = None,
        owner_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[{n.Domain}], int]:
        stmt = select({n.Domain}Model).where({n.Domain}Model.deleted_at.is_(None))
        count_stmt = select(func.count()).select_from({n.Domain}Model).where({n.Domain}Model.deleted_at.is_(None))

        if {n.domain}_id:
            stmt = stmt.where({n.Domain}Model.id == {n.domain}_id)
            count_stmt = count_stmt.where({n.Domain}Model.id == {n.domain}_id)
        if owner_id:
            stmt = stmt.where({n.Domain}Model.owner_id == owner_id)
            count_stmt = count_stmt.where({n.Domain}Model.owner_id == owner_id)
        if status:
            stmt = stmt.where({n.Domain}Model.status.in_(status))
            count_stmt = count_stmt.where({n.Domain}Model.status.in_(status))

        total = (await self._session.execute(count_stmt)).scalar_one()
        rows = (
            (await self._session.execute(stmt.order_by({n.Domain}Model.id.desc()).offset(page * take).limit(take)))
            .scalars()
            .all()
        )

        return [self._to_domain(row) for row in rows], total

    async def save(self, {n.domain}: {n.Domain}) -> None:
        existing = await self._session.get({n.Domain}Model, {n.domain}.{n.domain}_id)
        if existing:
            existing.status = {n.domain}.status.value
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(
                {n.Domain}Model(
                    id={n.domain}.{n.domain}_id,
                    owner_id={n.domain}.owner_id,
                    status={n.domain}.status.value,
                    created_at={n.domain}.created_at,
                )
            )

        events = {n.domain}.pull_events()
        if events:
            await self._outbox_writer.save_all(events)

        await self._session.flush()

    def _to_domain(self, row: {n.Domain}Model) -> {n.Domain}:
        return {n.Domain}(
            {n.domain}_id=row.id,
            owner_id=row.owner_id,
            status={n.Domain}Status(row.status),
            created_at=row.created_at,
        )
'''

    # ---- interface/rest/ ----
    files[f"{n.domain}/interface/rest/schemas.py"] = f"""from datetime import datetime

from pydantic import BaseModel


class Create{n.Domain}Response(BaseModel):
    {n.domain}_id: str
    owner_id: str
    status: str
    created_at: datetime


class Cancel{n.Domain}Request(BaseModel):
    reason: str


class Get{n.Domain}Response(BaseModel):
    {n.domain}_id: str
    owner_id: str
    status: str
    created_at: datetime
"""

    files[f"{n.domain}/interface/rest/{n.domain}_router.py"] = f'''from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....account.interface.rest.account_router import _outbox_relay
from ....auth.interface.rest.dependencies import CurrentUser, get_current_user
from ....common.rate_limit import limiter, rate_limit_config
from ....database import get_session
from ....outbox.outbox_relay import OutboxRelay
from ...application.command.cancel_{n.domain}_handler import Cancel{n.Domain}Command, Cancel{n.Domain}Handler
from ...application.command.create_{n.domain}_handler import Create{n.Domain}Command, Create{n.Domain}Handler
from ...application.query.get_{n.domain}_handler import Get{n.Domain}Handler, Get{n.Domain}Query
from ...domain.repository import {n.Domain}Query
from ...infrastructure.persistence.{n.domain}_repository import SqlAlchemy{n.Domain}Repository
from .schemas import Cancel{n.Domain}Request, Create{n.Domain}Response, Get{n.Domain}Response

router = APIRouter(prefix="/{n.domains_kebab}", tags=["{n.Domain}"], dependencies=[Depends(get_current_user)])


def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemy{n.Domain}Repository:
    return SqlAlchemy{n.Domain}Repository(session)


def _query_repo(session: AsyncSession = Depends(get_session)) -> {n.Domain}Query:
    return SqlAlchemy{n.Domain}Repository(session)


@router.post("", status_code=201, response_model=Create{n.Domain}Response)
@limiter.limit(rate_limit_config.write_limit)
async def create_{n.domain}(
    request: Request,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemy{n.Domain}Repository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> Create{n.Domain}Response:
    {n.domain} = await Create{n.Domain}Handler(repo, outbox_relay).execute(
        Create{n.Domain}Command(requester_id=current_user.user_id)
    )
    return Create{n.Domain}Response(
        {n.domain}_id={n.domain}.{n.domain}_id,
        owner_id={n.domain}.owner_id,
        status={n.domain}.status.value,
        created_at={n.domain}.created_at,
    )


@router.post("/{{{n.domain}_id}}/cancel", status_code=204)
@limiter.limit(rate_limit_config.write_limit)
async def cancel_{n.domain}(
    request: Request,
    {n.domain}_id: str,
    body: Cancel{n.Domain}Request,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemy{n.Domain}Repository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> None:
    await Cancel{n.Domain}Handler(repo, outbox_relay).execute(
        Cancel{n.Domain}Command({n.domain}_id={n.domain}_id, requester_id=current_user.user_id, reason=body.reason)
    )


@router.get("/{{{n.domain}_id}}", response_model=Get{n.Domain}Response)
async def get_{n.domain}(
    {n.domain}_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: {n.Domain}Query = Depends(_query_repo),
) -> Get{n.Domain}Response:
    result = await Get{n.Domain}Handler(repo).execute(
        Get{n.Domain}Query({n.domain}_id={n.domain}_id, requester_id=current_user.user_id)
    )
    return Get{n.Domain}Response(
        {n.domain}_id=result.{n.domain}_id,
        owner_id=result.owner_id,
        status=result.status,
        created_at=result.created_at,
    )
'''

    # ---- __init__.py — 이 저장소의 모든 패키지 디렉토리는 (namespace package가 아니라)
    # 명시적 __init__.py를 갖는다(account/card 전부 예외 없이). collect_py_files()가
    # __init__.py를 SKIP_FILES로 건너뛰어 harness가 이 파일의 존재 여부 자체를 검사하지는
    # 않지만, 없으면 이 저장소의 나머지 패키지와 구조가 어긋난다.
    package_dirs = sorted({os.path.dirname(rel_path) for rel_path in files})
    all_dirs: set[str] = set()
    for d in package_dirs:
        parts = d.split("/")
        for i in range(1, len(parts) + 1):
            all_dirs.add("/".join(parts[:i]))
    for d in sorted(all_dirs):
        files[f"{d}/__init__.py"] = ""

    return files


# ---------------------------------------------------------------------------
# Alembic 마이그레이션
# ---------------------------------------------------------------------------


def find_migration_head(versions_dir: str) -> str | None:
    """versions/ 아래 모든 리비전 파일을 읽어 down_revision으로 참조되지 않는 리비전(head)을 찾는다."""
    if not os.path.isdir(versions_dir):
        return None

    revisions: set[str] = set()
    down_revisions: set[str] = set()
    for name in os.listdir(versions_dir):
        if not name.endswith(".py"):
            continue
        with open(os.path.join(versions_dir, name), encoding="utf-8") as f:
            content = f.read()
        rev_match = re.search(r'^revision:\s*str\s*=\s*"([0-9a-f]+)"', content, re.MULTILINE)
        down_match = re.search(r'^down_revision.*=\s*"([0-9a-f]+)"', content, re.MULTILINE)
        if rev_match:
            revisions.add(rev_match.group(1))
        if down_match:
            down_revisions.add(down_match.group(1))

    heads = revisions - down_revisions
    if len(heads) == 1:
        return next(iter(heads))
    if len(heads) > 1:
        raise RuntimeError(f"마이그레이션 head가 여러 개입니다(브랜치 상태): {heads}")
    return None


def generate_migration(n: Names, down_revision: str | None) -> tuple[str, str]:
    revision = secrets.token_hex(6)
    create_date = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")
    down_revision_repr = f'"{down_revision}"' if down_revision else "None"
    content = f'''"""create {n.domains} table

Revision ID: {revision}
Revises: {down_revision or ""}
Create Date: {create_date}

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "{revision}"
down_revision: Union[str, Sequence[str], None] = {down_revision_repr}
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        "{n.domains}",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("owner_id", sa.String(), nullable=False),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.Column("deleted_at", sa.DateTime(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_table("{n.domains}")
'''
    filename = f"{revision}_create_{n.domains}_table.py"
    return filename, content


# ---------------------------------------------------------------------------
# 배선 (main.py / account_router.py / migrations/env.py)
# ---------------------------------------------------------------------------


def wire_main(main_path: str, n: Names) -> bool:
    if not os.path.isfile(main_path):
        print(f"main.py를 찾지 못해 자동 wiring을 건너뜁니다: {main_path}")
        return False

    with open(main_path, encoding="utf-8") as f:
        content = f.read()

    router_import = (
        f"from src.{n.domain}.interface.rest.{n.domain}_router import router as {n.domain}_router  # noqa: E402"
    )
    if router_import in content:
        print(f"main.py에 이미 {n.domain}_router가 등록돼 있어 건너뜁니다.")
        return False

    errors_import = f"from src.{n.domain}.domain.errors import {n.Domain}Error, {n.Domain}NotFoundError  # noqa: E402"

    # 마지막 "... import router as ..._router" 줄 다음에 라우터 import를 삽입하고, 그
    # 앞(마지막 domain.errors import 줄 다음)에 에러 import를 삽입한다.
    router_import_pattern = re.compile(r"^from src\..*import router as \w+_router {2}#\s*noqa:\s*E402$", re.MULTILINE)
    errors_import_pattern = re.compile(r"^from src\..*domain\.errors import .+#\s*noqa:\s*E402$", re.MULTILINE)
    router_import_lines = list(router_import_pattern.finditer(content))
    errors_import_lines = list(errors_import_pattern.finditer(content))

    if not router_import_lines or not errors_import_lines:
        print("main.py의 import 앵커를 찾지 못해 자동 wiring을 건너뜁니다. 스니펫을 수동으로 추가하세요.")
        print_main_snippet(n)
        return False

    last_errors_end = errors_import_lines[-1].end()
    content = content[:last_errors_end] + "\n" + errors_import + content[last_errors_end:]

    # router import 앵커는 위 삽입으로 오프셋이 밀렸으므로 다시 계산한다.
    router_import_lines = list(router_import_pattern.finditer(content))
    last_router_end = router_import_lines[-1].end()
    content = content[:last_router_end] + "\n" + router_import + content[last_router_end:]

    # app.include_router(...) 마지막 줄 다음에 삽입
    include_lines = list(re.finditer(r"^app\.include_router\(\w+_router\)$", content, re.MULTILINE))
    if include_lines:
        last_include_end = include_lines[-1].end()
        content = content[:last_include_end] + f"\napp.include_router({n.domain}_router)" + content[last_include_end:]

    # RequestValidationError 핸들러 바로 앞에 새 예외 핸들러 삽입 (구체 타입을 상위 타입보다 먼저 등록)
    marker = "@app.exception_handler(RequestValidationError)"
    marker_idx = content.find(marker)
    if marker_idx == -1:
        print("main.py에서 RequestValidationError 핸들러를 찾지 못해 예외 핸들러 wiring을 건너뜁니다.")
    else:
        handlers = f"""@app.exception_handler({n.Domain}NotFoundError)
async def {n.domain}_not_found_handler(request: Request, exc: {n.Domain}NotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler({n.Domain}Error)
async def {n.domain}_error_handler(request: Request, exc: {n.Domain}Error) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))


"""
        content = content[:marker_idx] + handlers + content[marker_idx:]

    with open(main_path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"main.py에 {n.Domain} 라우터/예외 핸들러 등록 완료: {main_path}")
    return True


def wire_account_router(account_router_path: str, n: Names) -> bool:
    if not os.path.isfile(account_router_path):
        print(f"account_router.py를 찾지 못해 OutboxRelay 자동 wiring을 건너뜁니다: {account_router_path}")
        return False

    with open(account_router_path, encoding="utf-8") as f:
        content = f.read()

    handler_import = (
        f"from ....{n.domain}.application.event.{n.domain}_cancelled_event_handler "
        f"import {n.Domain}CancelledEventHandler"
    )
    if handler_import in content:
        print(f"account_router.py에 이미 {n.Domain}CancelledEventHandler가 등록돼 있어 건너뜁니다.")
        return False

    # account 자신의 event handler import 블록 마지막 줄 다음에 삽입 (isort 그룹 유지)
    event_import_lines = list(
        re.finditer(r"^from \.\.\.application\.event\..+ import \w+EventHandler$", content, re.MULTILINE)
    )
    if not event_import_lines:
        print("account_router.py의 event handler import 앵커를 찾지 못해 wiring을 건너뜁니다.")
        return False
    last_end = event_import_lines[-1].end()
    content = content[:last_end] + "\n" + handler_import + content[last_end:]

    # handlers={ 딕셔너리의 첫 항목으로 새 도메인 이벤트를 등록한다 — 이 dict가 프로세스 전체의
    # 단일 조립 지점(공유 OutboxRelay)이다.
    entry = f'            "{n.Domain}Cancelled": {n.Domain}CancelledEventHandler().handle,\n'
    content, count = re.subn(r"(handlers=\{\n)", r"\1" + entry, content, count=1)
    if count == 0:
        print("account_router.py에서 handlers={ 딕셔너리를 찾지 못해 등록을 건너뜁니다.")
        return False

    with open(account_router_path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"account_router.py의 공유 OutboxRelay에 {n.Domain}CancelledEventHandler 등록 완료: {account_router_path}")
    return True


def wire_migrations_env(env_path: str, n: Names) -> bool:
    if not os.path.isfile(env_path):
        print(f"migrations/env.py를 찾지 못해 자동 wiring을 건너뜁니다: {env_path}")
        return False

    with open(env_path, encoding="utf-8") as f:
        content = f.read()

    model_import = f"import src.{n.domain}.infrastructure.persistence.{n.domain}_repository  # noqa: F401"
    if model_import in content:
        print(f"migrations/env.py에 이미 {n.domain}_repository가 등록돼 있어 건너뜁니다.")
        return False

    import_lines = list(re.finditer(r"^import src\..+# noqa: F401$", content, re.MULTILINE))
    if not import_lines:
        print("migrations/env.py의 import 앵커를 찾지 못해 자동 wiring을 건너뜁니다.")
        return False
    last_end = import_lines[-1].end()
    content = content[:last_end] + "\n" + model_import + content[last_end:]

    with open(env_path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"migrations/env.py에 {n.domain}_repository 모델 import 등록 완료: {env_path}")
    return True


def print_main_snippet(n: Names) -> None:
    print("")
    print("--- main.py에 수동으로 추가할 내용 (--wire를 주지 않았으므로 자동 적용 안 됨) ---")
    print("")
    print(f"from src.{n.domain}.domain.errors import {n.Domain}Error, {n.Domain}NotFoundError  # noqa: E402")
    print(f"from src.{n.domain}.interface.rest.{n.domain}_router import router as {n.domain}_router  # noqa: E402")
    print("")
    print(f"app.include_router({n.domain}_router)")
    print("")
    print(f"@app.exception_handler({n.Domain}NotFoundError)")
    print(f"async def {n.domain}_not_found_handler(request, exc) -> JSONResponse: ...")
    print(f"@app.exception_handler({n.Domain}Error)")
    print(f"async def {n.domain}_error_handler(request, exc) -> JSONResponse: ...")
    print("")


def print_account_router_snippet(n: Names) -> None:
    print("--- account_router.py의 _outbox_relay()에 수동으로 추가할 내용 ---")
    print("")
    print(
        f"from ....{n.domain}.application.event.{n.domain}_cancelled_event_handler "
        f"import {n.Domain}CancelledEventHandler"
    )
    print("")
    print("  # handlers={...} 딕셔너리에 추가:")
    print(f'    "{n.Domain}Cancelled": {n.Domain}CancelledEventHandler().handle,')
    print("")


def print_env_snippet(n: Names) -> None:
    print("--- migrations/env.py에 수동으로 추가할 내용 ---")
    print("")
    print(f"import src.{n.domain}.infrastructure.persistence.{n.domain}_repository  # noqa: F401")
    print("")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def write_file(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def run_ruff_fix(paths: list[str]) -> None:
    """생성/수정된 파일에 `ruff check --fix`와 `ruff format`을 실행해 import 정렬·포맷을
    정리한다.

    새 도메인 자신의 파일은 템플릿에서 이미 올바른 순서로 만들어지지만, main.py/
    account_router.py/migrations/env.py 같은 기존 공유 파일에 새 import 줄을 텍스트로
    끼워 넣을 때는 "어디에 삽입해야 알파벳 순서가 정확히 맞는가"가 도메인 이름마다 달라진다
    (예: "card" 다음 "common" 앞 vs "database" 다음 "outbox" 앞 등, 도메인 이름의 알파벳
    위치에 따라 삽입 지점이 매번 바뀐다). 정확한 위치를 텍스트 삽입으로 매번 재현하는 대신,
    문법적으로 안전한 위치에 삽입한 뒤 ruff의 isort 구현이 실제로 재정렬하게 한다 — 이렇게
    하면 도메인 이름과 무관하게 항상 올바른 순서가 보장된다.
    """
    existing = [p for p in paths if os.path.isfile(p)]
    if not existing:
        return
    if shutil.which("ruff") is None:
        print("ruff를 찾지 못해 자동 포맷을 건너뜁니다 — 수동으로 `ruff check --fix .`/`ruff format .`를 실행하세요.")
        return
    subprocess.run(["ruff", "check", "--fix", "--quiet", *existing], check=False)
    subprocess.run(["ruff", "format", "--quiet", *existing], check=False)


def main() -> None:
    parser = argparse.ArgumentParser(description="새 도메인 스캐폴딩 생성기")
    parser.add_argument("domain_name", help="PascalCase 도메인 이름 (예: Coupon, LoyaltyCategory)")
    parser.add_argument("--out", dest="out", default=None, help="생성 대상 src/ 디렉토리 (기본: ../examples/src)")
    parser.add_argument("--wire", action="store_true", help="main.py/account_router.py/migrations를 자동 배선")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    target_src_dir = args.out or os.path.join(script_dir, "..", "examples", "src")
    target_src_dir = os.path.abspath(target_src_dir)
    project_root = os.path.dirname(target_src_dir)

    n = Names(args.domain_name)
    files = generate_files(n)

    for rel_path, content in files.items():
        write_file(os.path.join(target_src_dir, rel_path), content)

    versions_dir = os.path.join(project_root, "migrations", "versions")
    head = find_migration_head(versions_dir)
    migration_filename, migration_content = generate_migration(n, head)

    print(f"{n.Domain} 도메인 생성 완료: {os.path.join(target_src_dir, n.domain)}/ ({len(files)}개 파일)")
    print(f"REST 경로: /{n.domains_kebab} (POST 생성, GET/:{n.domain}_id 조회, POST /:{n.domain}_id/cancel 취소)")
    print("")
    print("참고: 나이브 복수형 규칙(+s / +es / y→ies)을 snake_case 위에서 적용했습니다 — 진짜")
    print(f"  불규칙 복수형(person→people 등) 도메인이면 {n.Domains}/{n.domains} 등을 수동으로 다듬어야 합니다.")

    touched_paths = [os.path.join(target_src_dir, rel_path) for rel_path in files]

    if args.wire:
        migration_path = os.path.join(versions_dir, migration_filename)
        write_file(migration_path, migration_content)
        print(f"Alembic 마이그레이션 생성 완료: {migration_filename} (down_revision={head})")
        main_path = os.path.join(project_root, "main.py")
        account_router_path = os.path.join(target_src_dir, "account", "interface", "rest", "account_router.py")
        env_path = os.path.join(project_root, "migrations", "env.py")
        wire_main(main_path, n)
        wire_account_router(account_router_path, n)
        wire_migrations_env(env_path, n)
        touched_paths += [main_path, account_router_path, env_path]
    else:
        print("")
        print(f"--- migrations/versions/에 수동으로 추가할 마이그레이션 파일: {migration_filename} ---")
        print(f"(down_revision={head} — --wire 없이 실행했으므로 파일을 만들지 않았습니다)")
        print_main_snippet(n)
        print_account_router_snippet(n)
        print_env_snippet(n)

    run_ruff_fix(touched_paths)

    print("")
    print("다음: ruff check . && ruff format --check . && bash harness.sh <projectRoot>로 검증하세요.")


if __name__ == "__main__":
    sys.exit(main())
