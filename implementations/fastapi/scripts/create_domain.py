#!/usr/bin/env python3
"""A scaffolding generator for a new domain.

Based on "The Practical Implementation Template" in docs/reference.md and the actual code
in examples/src/account·card/, generates in one shot: an Aggregate (a single status field,
PENDING→ACTIVE/CANCELLED) + CQRS Command/Query Handlers + one domain event (published on
cancel) + a Repository (ABC/implementation) + a Router + Pydantic schemas + an Alembic
migration.

FastAPI has no DI container like NestJS's CommandBus/CqrsModule/@Module — instead, a
Depends factory function is the assembly point. Publishing/receiving Outbox → SQS runs
independently and periodically across the whole app via OutboxPoller/OutboxConsumer (a
Command Handler never references this at all — domain-events.md's principle forbidding
synchronous draining), and eventType → handler routing has a single composition root for
the entire process: `build_event_handlers()` in `src/outbox/event_handlers.py` (unlike the
older approach of a dedicated Relay per domain). So beyond creating the new domain
directory, this generator must also modify three existing files together — main.py
(router registration), event_handlers.py (the shared composition root
`build_event_handlers()`), and migrations/env.py (the model import Alembic needs to
detect). By default it doesn't modify them and only prints the snippets to paste in; only
with --wire does it actually fix the files (since one might not want an existing project
file modified arbitrarily, the safer option is the default).

Usage:
  python3 scripts/create_domain.py <PascalCaseDomainName> [--out <targetSrcDir>] [--wire]

Examples:
  python3 scripts/create_domain.py Coupon
    → generates under ../examples/src/coupon/ (the script's default target); main.py/
      event_handlers.py/migrations/env.py are left untouched, only the snippets to paste
      in are printed
  python3 scripts/create_domain.py Coupon --out /tmp/scratch-app/src --wire
    → generates under the specified src/ + automatically wires up main.py/event_handlers.py/
      migrations/env.py/migrations/versions/ too

Verification: after generating, confirm with `ruff check .`, `ruff format --check .`,
`bash harness.sh <projectRoot>`.
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
# Name conversion
# ---------------------------------------------------------------------------


def to_pascal_case(raw: str) -> str:
    if not raw:
        return raw
    return raw[0].upper() + raw[1:]


def to_snake_case(pascal: str) -> str:
    # Inserts '_' before every uppercase letter except the first, then lowercases everything
    # — "LoyaltyCategory" -> "loyalty_category"
    return re.sub(r"(?<!^)(?=[A-Z])", "_", pascal).lower()


def snake_to_pascal(snake: str) -> str:
    return "".join(part[:1].upper() + part[1:] for part in snake.split("_") if part)


# A very simple rule-based pluralization — the run's output notes that a genuinely
# irregular plural (e.g. beyond Category → Categories, something like person → people)
# must be fixed manually after generation. Since it decides based only on the suffix over
# a snake_case string (where word boundaries are preserved via '_'), even a multi-word
# domain name gets exactly its last word pluralized correctly — to avoid the problem where
# naively appending a suffix over kebab-case or PascalCase notation breaks word boundaries
# (e.g. "loyalty-category" → "loyalty-categorys"), the plural is computed in snake_case
# first, then converted back to kebab/PascalCase.
def naive_pluralize_snake(snake: str) -> str:
    if re.search(r"[sxz]$", snake) or re.search(r"[cs]h$", snake):
        return snake + "es"
    if re.search(r"[^aeiou]y$", snake):
        return snake[:-1] + "ies"
    return snake + "s"


def sorted_dotted_imports(prefix: str, entries: list[tuple[str, str]]) -> str:
    """Returns `from {prefix}.<module> import <names>` lines, sorted alphabetically by module name.

    Since the module name's alphabetical order changes depending on the domain name (e.g.
    "coupon" < "repository" but "voucher" > "repository"), hardcoding the order in the
    template would create a latent bug where an isort violation only shows up for certain
    domain names — it's always actually sorted here instead.
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
# File generation
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
        super().__init__(f"{n.Domain} not found: {{{n.domain}_id}}")
        self.{n.domain}_id = {n.domain}_id


class {n.Domain}AlreadyCancelledError({n.Domain}Error):
    code = {n.Domain}ErrorCode.{n.DOMAIN_SCREAM}_ALREADY_CANCELLED

    def __init__(self) -> None:
        super().__init__("The {n.Domain} is already cancelled.")
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

    # An example of a simple state transition that publishes no event — a change that
    # doesn't need a domain event just changes the state like this.
    def activate(self) -> None:
        self.status = {n.Domain}Status.ACTIVE

    # An example of a state transition that publishes an event.
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
    """A read-only interface — for the Query Handler only. Never exposes a write method
    such as `save_{n.domain}()` (see cqrs-pattern.md). Shares its method signatures with
    `{n.Domain}Repository` (the write model) but is a separate contract — a Query Handler
    must always depend only on this type.
    """

    @abstractmethod
    async def find_{n.domains}(
        self,
        page: int,
        take: int,
        {n.domain}_id: str | None = None,
        owner_id: str | None = None,
        status: list[str] | None = None,
    ) -> tuple[list[{n.Domain}], int]: ...


class {n.Domain}Repository({n.Domain}Query, ABC):
    @abstractmethod
    async def save_{n.domain}(self, {n.domain}: {n.Domain}) -> None: ...
'''

    # ---- application/command/ ----
    create_handler_local_imports = sorted_dotted_imports(
        "...domain", [(n.domain, n.Domain), ("repository", f"{n.Domain}Repository")]
    )
    files[f"{n.domain}/application/command/create_{n.domain}_handler.py"] = f"""from dataclasses import dataclass

{create_handler_local_imports}


@dataclass
class Create{n.Domain}Command:
    requester_id: str


class Create{n.Domain}Handler:
    def __init__(self, repo: {n.Domain}Repository) -> None:
        self._repo = repo

    async def execute(self, cmd: Create{n.Domain}Command) -> {n.Domain}:
        {n.domain} = {n.Domain}.create(owner_id=cmd.requester_id)
        await self._repo.save_{n.domain}({n.domain})
        return {n.domain}
"""

    files[f"{n.domain}/application/command/cancel_{n.domain}_handler.py"] = f"""from dataclasses import dataclass

from ...domain.errors import {n.Domain}NotFoundError
from ...domain.repository import {n.Domain}Repository


@dataclass
class Cancel{n.Domain}Command:
    {n.domain}_id: str
    requester_id: str
    reason: str


class Cancel{n.Domain}Handler:
    def __init__(self, repo: {n.Domain}Repository) -> None:
        self._repo = repo

    async def execute(self, cmd: Cancel{n.Domain}Command) -> None:
        {n.domains}, _ = await self._repo.find_{n.domains}(
            page=0, take=1, {n.domain}_id=cmd.{n.domain}_id, owner_id=cmd.requester_id
        )
        {n.domain} = {n.domains}[0] if {n.domains} else None
        if {n.domain} is None:
            raise {n.Domain}NotFoundError(cmd.{n.domain}_id)
        {n.domain}.cancel(cmd.reason)
        await self._repo.save_{n.domain}({n.domain})
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
        {n.domains}, _ = await self._query.find_{n.domains}(
            page=0, take=1, {n.domain}_id=query.{n.domain}_id, owner_id=query.requester_id
        )
        {n.domain} = {n.domains}[0] if {n.domains} else None
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
    """Implement follow-up processing for a cancelled {n.Domain} here (a notification,
    publishing an Integration Event to another BC, etc.). At the scaffolding stage, this
    only logs.
    """

    async def handle(self, payload: dict) -> None:
        logger.info(
            "{n.Domain} cancelled: %s_id=%s reason=%s", "{n.domain}", payload["{n.domain}_id"], payload["reason"]
        )
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
        # deferred import — since outbox_model.py imports account_repository.py's Base,
        # importing OutboxWriter at the module's top level would create a circular import
        # (see "Python's circular imports" in module-pattern.md).
        from ....outbox.outbox_writer import OutboxWriter

        self._session = session
        self._outbox_writer = OutboxWriter(session)

    async def find_{n.domains}(
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

    async def save_{n.domain}(self, {n.domain}: {n.Domain}) -> None:
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

from pydantic import BaseModel, Field


class Create{n.Domain}Response(BaseModel):
    {n.domain}_id: str = Field(description="The unique identifier of the newly created {n.domain}.")
    owner_id: str = Field(description="The `user_id` of the authenticated requester who owns this {n.domain}.")
    status: str = Field(description="The {n.domain}'s lifecycle status (`PENDING`, `ACTIVE`, or `CANCELLED`).")
    created_at: datetime = Field(description="When the {n.domain} was created, in UTC.")


class Cancel{n.Domain}Request(BaseModel):
    reason: str = Field(description="A human-readable reason the {n.domain} is being cancelled.")


class Get{n.Domain}Response(BaseModel):
    {n.domain}_id: str = Field(description="The unique identifier of the {n.domain}.")
    owner_id: str = Field(description="The `user_id` of the authenticated requester who owns this {n.domain}.")
    status: str = Field(description="The {n.domain}'s lifecycle status (`PENDING`, `ACTIVE`, or `CANCELLED`).")
    created_at: datetime = Field(description="When the {n.domain} was created, in UTC.")
"""

    files[f"{n.domain}/interface/rest/{n.domain}_router.py"] = f'''from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ....auth.interface.rest.dependencies import CurrentUser, get_current_user
from ....common.error_response import ErrorResponse
from ....common.rate_limit import limiter, rate_limit_config
from ....database import get_session
from ...application.command.cancel_{n.domain}_handler import Cancel{n.Domain}Command, Cancel{n.Domain}Handler
from ...application.command.create_{n.domain}_handler import Create{n.Domain}Command, Create{n.Domain}Handler
from ...application.query.get_{n.domain}_handler import Get{n.Domain}Handler, Get{n.Domain}Query
from ...domain.repository import {n.Domain}Query
from ...infrastructure.persistence.{n.domain}_repository import SqlAlchemy{n.Domain}Repository
from .schemas import Cancel{n.Domain}Request, Create{n.Domain}Response, Get{n.Domain}Response

# A router-level `responses=` is merged into every route on this router by FastAPI — every
# route below requires get_current_user, so 401 applies uniformly without repeating it
# per-route (api-response.md "Machine-readable API documentation (OpenAPI)").
router = APIRouter(
    prefix="/{n.domains_kebab}",
    tags=["{n.Domain}"],
    dependencies=[Depends(get_current_user)],
    responses={{
        401: {{
            "model": ErrorResponse,
            "description": "The bearer token is missing, malformed, or invalid (`INVALID_TOKEN`).",
        }}
    }},
)


def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemy{n.Domain}Repository:
    return SqlAlchemy{n.Domain}Repository(session)


def _query_repo(session: AsyncSession = Depends(get_session)) -> {n.Domain}Query:
    return SqlAlchemy{n.Domain}Repository(session)


@router.post(
    "",
    status_code=201,
    response_model=Create{n.Domain}Response,
    summary="Create a new {n.Domain}",
    description="Creates a new {n.Domain} owned by the authenticated requester, starting in `PENDING` status.",
)
@limiter.limit(rate_limit_config.write_limit)
async def create_{n.domain}(
    request: Request,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemy{n.Domain}Repository = Depends(_repo),
) -> Create{n.Domain}Response:
    {n.domain} = await Create{n.Domain}Handler(repo).execute(
        Create{n.Domain}Command(requester_id=current_user.user_id)
    )
    return Create{n.Domain}Response(
        {n.domain}_id={n.domain}.{n.domain}_id,
        owner_id={n.domain}.owner_id,
        status={n.domain}.status.value,
        created_at={n.domain}.created_at,
    )


@router.post(
    "/{{{n.domain}_id}}/cancel",
    status_code=204,
    summary="Cancel a {n.Domain}",
    description="Cancels a {n.Domain} that isn't already cancelled.",
    responses={{
        400: {{
            "model": ErrorResponse,
            "description": (
                "The {n.Domain} is already cancelled (`{n.DOMAIN_SCREAM}_ALREADY_CANCELLED`)."
            ),
        }},
        404: {{
            "model": ErrorResponse,
            "description": (
                "No {n.Domain} exists with the given `{n.domain}_id` for this requester "
                "(`{n.DOMAIN_SCREAM}_NOT_FOUND`)."
            ),
        }},
    }},
)
@limiter.limit(rate_limit_config.write_limit)
async def cancel_{n.domain}(
    request: Request,
    {n.domain}_id: str,
    body: Cancel{n.Domain}Request,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemy{n.Domain}Repository = Depends(_repo),
) -> None:
    await Cancel{n.Domain}Handler(repo).execute(
        Cancel{n.Domain}Command({n.domain}_id={n.domain}_id, requester_id=current_user.user_id, reason=body.reason)
    )


@router.get(
    "/{{{n.domain}_id}}",
    response_model=Get{n.Domain}Response,
    summary="Look up a {n.Domain}",
    description="Returns the {n.Domain} only if it belongs to the authenticated requester.",
    responses={{
        404: {{
            "model": ErrorResponse,
            "description": (
                "No {n.Domain} exists with the given `{n.domain}_id` for this requester "
                "(`{n.DOMAIN_SCREAM}_NOT_FOUND`)."
            ),
        }}
    }},
)
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

    # ---- __init__.py — every package directory in this repository has an explicit
    # __init__.py (not a namespace package) — account/card, all without exception.
    # collect_py_files() skips __init__.py via SKIP_FILES, so the harness doesn't check
    # for this file's existence itself, but omitting it would leave the structure out of
    # sync with the rest of this repository's packages.
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
# Alembic migrations
# ---------------------------------------------------------------------------


def find_migration_head(versions_dir: str) -> str | None:
    """Reads every revision file under versions/ and finds the revision (the head) that isn't
    referenced as any down_revision."""
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
        raise RuntimeError(f"There are multiple migration heads (a branched state): {heads}")
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
# Wiring (main.py / event_handlers.py / migrations/env.py)
# ---------------------------------------------------------------------------


def wire_main(main_path: str, n: Names) -> bool:
    if not os.path.isfile(main_path):
        print(f"Cannot find main.py, skipping automatic wiring: {main_path}")
        return False

    with open(main_path, encoding="utf-8") as f:
        content = f.read()

    router_import = (
        f"from src.{n.domain}.interface.rest.{n.domain}_router import router as {n.domain}_router  # noqa: E402"
    )
    if router_import in content:
        print(f"main.py already has {n.domain}_router registered, skipping.")
        return False

    errors_import = f"from src.{n.domain}.domain.errors import {n.Domain}Error, {n.Domain}NotFoundError  # noqa: E402"

    # Insert the router import right after the last "... import router as ..._router" line, and
    # insert the error import right before it (right after the last domain.errors import line).
    router_import_pattern = re.compile(r"^from src\..*import router as \w+_router {2}#\s*noqa:\s*E402$", re.MULTILINE)
    errors_import_pattern = re.compile(r"^from src\..*domain\.errors import .+#\s*noqa:\s*E402$", re.MULTILINE)
    router_import_lines = list(router_import_pattern.finditer(content))
    errors_import_lines = list(errors_import_pattern.finditer(content))

    if not router_import_lines or not errors_import_lines:
        print("Cannot find the import anchor in main.py, skipping automatic wiring. Add the snippet manually.")
        print_main_snippet(n)
        return False

    last_errors_end = errors_import_lines[-1].end()
    content = content[:last_errors_end] + "\n" + errors_import + content[last_errors_end:]

    # The router import anchor's offset shifted due to the insertion above, so recompute it.
    router_import_lines = list(router_import_pattern.finditer(content))
    last_router_end = router_import_lines[-1].end()
    content = content[:last_router_end] + "\n" + router_import + content[last_router_end:]

    # Insert right after the last app.include_router(...) line
    include_lines = list(re.finditer(r"^app\.include_router\(\w+_router\)$", content, re.MULTILINE))
    if include_lines:
        last_include_end = include_lines[-1].end()
        content = content[:last_include_end] + f"\napp.include_router({n.domain}_router)" + content[last_include_end:]

    # Insert the new exception handler right before the RequestValidationError handler
    # (register the concrete type before the more general one)
    marker = "@app.exception_handler(RequestValidationError)"
    marker_idx = content.find(marker)
    if marker_idx == -1:
        print("Cannot find the RequestValidationError handler in main.py, skipping exception handler wiring.")
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
    print(f"Registered {n.Domain} router/exception handlers in main.py: {main_path}")
    return True


def wire_event_handlers(event_handlers_path: str, n: Names) -> bool:
    """Registers the new domain's Domain Event handler in `build_event_handlers()` inside
    `src/outbox/event_handlers.py` — this function is the single, process-wide composition
    root. OutboxConsumer looks up messages it receives from SQS in this dict by eventType and
    invokes them (see domain-events.md. Before the 2026-07 async migration, this used to patch
    the `_outbox_relay()` factory in `account_router.py` instead).
    """
    if not os.path.isfile(event_handlers_path):
        print(f"Cannot find event_handlers.py, skipping automatic event handler wiring: {event_handlers_path}")
        return False

    with open(event_handlers_path, encoding="utf-8") as f:
        content = f.read()

    handler_import = (
        f"from ..{n.domain}.application.event.{n.domain}_cancelled_event_handler import {n.Domain}CancelledEventHandler"
    )
    if handler_import in content:
        print(f"event_handlers.py already has {n.Domain}CancelledEventHandler registered, skipping.")
        return False

    # Insert right after the last line of another domain's event handler import block (keeps the isort group)
    event_import_lines = list(
        re.finditer(r"^from \.\.\w+\.application\.event\..+ import \w+EventHandler$", content, re.MULTILINE)
    )
    if not event_import_lines:
        print("Cannot find the event handler import anchor in event_handlers.py, skipping wiring.")
        return False
    last_end = event_import_lines[-1].end()
    content = content[:last_end] + "\n" + handler_import + content[last_end:]

    # Registers the new domain event as the first entry of the return { dictionary.
    entry = f'        "{n.Domain}Cancelled": {n.Domain}CancelledEventHandler().handle,\n'
    content, count = re.subn(r"(    return \{\n)", r"\1" + entry, content, count=1)
    if count == 0:
        print("Cannot find the return { dictionary in event_handlers.py, skipping registration.")
        return False

    with open(event_handlers_path, "w", encoding="utf-8") as f:
        f.write(content)
    print(
        f"Registered {n.Domain}CancelledEventHandler in event_handlers.py's build_event_handlers(): "
        f"{event_handlers_path}"
    )
    return True


def wire_migrations_env(env_path: str, n: Names) -> bool:
    if not os.path.isfile(env_path):
        print(f"Cannot find migrations/env.py, skipping automatic wiring: {env_path}")
        return False

    with open(env_path, encoding="utf-8") as f:
        content = f.read()

    model_import = f"import src.{n.domain}.infrastructure.persistence.{n.domain}_repository  # noqa: F401"
    if model_import in content:
        print(f"migrations/env.py already has {n.domain}_repository registered, skipping.")
        return False

    import_lines = list(re.finditer(r"^import src\..+# noqa: F401$", content, re.MULTILINE))
    if not import_lines:
        print("Cannot find the import anchor in migrations/env.py, skipping automatic wiring.")
        return False
    last_end = import_lines[-1].end()
    content = content[:last_end] + "\n" + model_import + content[last_end:]

    with open(env_path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"Registered the {n.domain}_repository model import in migrations/env.py: {env_path}")
    return True


def print_main_snippet(n: Names) -> None:
    print("")
    print("--- Add this manually to main.py (not applied automatically since --wire was not given) ---")
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


def print_event_handlers_snippet(n: Names) -> None:
    print("--- Add this manually to build_event_handlers() in src/outbox/event_handlers.py ---")
    print("")
    print(
        f"from ..{n.domain}.application.event.{n.domain}_cancelled_event_handler import {n.Domain}CancelledEventHandler"
    )
    print("")
    print("  # Add to the return {...} dictionary:")
    print(f'    "{n.Domain}Cancelled": {n.Domain}CancelledEventHandler().handle,')
    print("")


def print_env_snippet(n: Names) -> None:
    print("--- Add this manually to migrations/env.py ---")
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
    """Runs `ruff check --fix` and `ruff format` on the generated/modified files to clean up
    import ordering and formatting.

    The new domain's own files are already generated in the correct order by the template, but
    when new import lines are inserted as text into existing shared files such as main.py/
    event_handlers.py/migrations/env.py, "where exactly does it need to go to be alphabetically
    correct" varies by domain name (e.g. after "card" and before "common" vs. after "database"
    and before "outbox" — the insertion point shifts depending on the domain name's alphabetical
    position). Rather than trying to reproduce the exact position via text insertion every time,
    this inserts at a syntactically safe location and then lets ruff's isort implementation
    actually re-sort it — this guarantees the correct order regardless of the domain name.
    """
    existing = [p for p in paths if os.path.isfile(p)]
    if not existing:
        return
    if shutil.which("ruff") is None:
        print("Cannot find ruff, skipping automatic formatting — run `ruff check --fix .`/`ruff format .` manually.")
        return
    subprocess.run(["ruff", "check", "--fix", "--quiet", *existing], check=False)
    subprocess.run(["ruff", "format", "--quiet", *existing], check=False)


def main() -> None:
    parser = argparse.ArgumentParser(description="New domain scaffolding generator")
    parser.add_argument("domain_name", help="PascalCase domain name (e.g. Coupon, LoyaltyCategory)")
    parser.add_argument("--out", dest="out", default=None, help="Target src/ directory (default: ../examples/src)")
    parser.add_argument(
        "--wire", action="store_true", help="Automatically wire up main.py/event_handlers.py/migrations"
    )
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

    print(f"Generated the {n.Domain} domain: {os.path.join(target_src_dir, n.domain)}/ ({len(files)} files)")
    print(
        f"REST path: /{n.domains_kebab} (POST to create, GET /:{n.domain}_id to look up, "
        f"POST /:{n.domain}_id/cancel to cancel)"
    )
    print("")
    print("Note: a naive pluralization rule (+s / +es / y->ies) was applied on top of snake_case — for a")
    print(
        f"  domain with a genuinely irregular plural (e.g. person->people), manually adjust "
        f"{n.Domains}/{n.domains} etc."
    )

    touched_paths = [os.path.join(target_src_dir, rel_path) for rel_path in files]

    if args.wire:
        migration_path = os.path.join(versions_dir, migration_filename)
        write_file(migration_path, migration_content)
        print(f"Generated the Alembic migration: {migration_filename} (down_revision={head})")
        main_path = os.path.join(project_root, "main.py")
        event_handlers_path = os.path.join(target_src_dir, "outbox", "event_handlers.py")
        env_path = os.path.join(project_root, "migrations", "env.py")
        wire_main(main_path, n)
        wire_event_handlers(event_handlers_path, n)
        wire_migrations_env(env_path, n)
        touched_paths += [main_path, event_handlers_path, env_path]
    else:
        print("")
        print(f"--- Migration file to add manually under migrations/versions/: {migration_filename} ---")
        print(f"(down_revision={head} — the file was not created since --wire was not given)")
        print_main_snippet(n)
        print_event_handlers_snippet(n)
        print_env_snippet(n)

    run_ruff_fix(touched_paths)

    print("")
    print("Next: verify with ruff check . && ruff format --check . && bash harness.sh <projectRoot>.")


if __name__ == "__main__":
    sys.exit(main())
