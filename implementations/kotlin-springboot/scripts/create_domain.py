#!/usr/bin/env python3
"""New domain scaffolding generator (kotlin-springboot).

Generates, in one shot, a minimal Aggregate (a single status field, PENDING -> ACTIVE/CANCELLED)
that follows the practical implementation template in docs/reference.md (the Account/Card
examples) + a Command/Query Service (CQRS, no Handler/CommandBus) + one domain event
(published only by cancel()) + Repository + REST Controller + DTO + a Flyway migration.

Shares the same design philosophy as nestjs's scripts/create-domain.js (only the domain name is
pulled out as a reusable parameter), but thanks to Spring's component scanning
(@Service/@Component/@RestController/@Repository are auto-collected across the whole classpath),
kotlin-springboot has no central registration file at all like nestjs's
`@Module({ providers: [...] })` — as long as the new domain files are generated in the correct
location with the correct annotations, Spring finds them on its own.

**Two exceptions**: EventHandlerRegistry (outbox/EventHandlerRegistry.kt; before the 2026-07
async migration it was named OutboxRelay) and GlobalExceptionHandler
(common/GlobalExceptionHandler.kt) are both structured as "shared infrastructure that still
explicitly injects each individual handler via the constructor" (not Spring's automatic
List<Interface> collection pattern), so these two files must be edited by hand for every new
domain. The --wire option automatically applies the patch to these two files (without the
option, only the snippets to paste in are printed to the console — you might not want the
script arbitrarily modifying existing files, so the default is the safe option).

Usage:
  python3 scripts/create-domain.py <PascalCaseDomainName> [--project-root <gradle module root>] [--wire]

Examples:
  python3 scripts/create-domain.py Coupon
    -> generates under ../examples/src/main/kotlin/com/example/accountservice/coupon/ (default target)
  python3 scripts/create-domain.py LoyaltyCategory --project-root /tmp/scratch-app --wire
    -> generates under the given project root (a Gradle module containing
       src/main/kotlin/src/main/resources) + auto-patches EventHandlerRegistry.kt/
       GlobalExceptionHandler.kt + adds a Flyway migration

Without --wire, EventHandlerRegistry.kt/GlobalExceptionHandler.kt are left untouched, and only
the content to paste in is printed to the console. The Flyway migration is append-only
(it never modifies an existing file), so it's always generated regardless of --wire.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

# ---------------------------------------------------------------------------
# Name conversion
# ---------------------------------------------------------------------------


def to_pascal(raw: str) -> str:
    if not raw:
        raise ValueError("Domain name is empty.")
    return raw[0].upper() + raw[1:]


def to_camel(raw: str) -> str:
    if not raw:
        raise ValueError("Domain name is empty.")
    return raw[0].lower() + raw[1:]


_BOUNDARY = re.compile(r"([a-z0-9])([A-Z])")


def to_kebab(pascal: str) -> str:
    return _BOUNDARY.sub(r"\1-\2", pascal).lower()


def to_snake(pascal: str) -> str:
    return _BOUNDARY.sub(r"\1_\2", pascal).lower()


def to_scream(pascal: str) -> str:
    return _BOUNDARY.sub(r"\1_\2", pascal).upper()


# A very simple rule-based pluralizer — irregular plurals must be fixed by hand after generation.
# Same rule as nestjs's naivePluralize (+s / +es / consonant+y -> ies).
def naive_pluralize(word: str) -> str:
    if re.search(r"[sxz]$", word) or re.search(r"[cs]h$", word):
        return f"{word}es"
    if re.search(r"[^aeiouAEIOU]y$", word):
        return f"{word[:-1]}ies"
    return f"{word}s"


def _insert_imports_sorted(content: str, new_import_lines: list[str]) -> str:
    """Insert new import lines into the existing import block, then re-sort the whole block alphabetically.

    Simply appending to the end of the import block would fail ktlint (standard:import-ordering,
    which requires "alphabetical order, no blank lines in between") — EventHandlerRegistry.kt/
    GlobalExceptionHandler.kt have no java/javax/kotlin/aliased imports at all, so a plain
    string sort is sufficient.
    """
    lines = content.split("\n")
    import_idx = [i for i, line in enumerate(lines) if line.startswith("import ")]
    if not import_idx:
        return content
    start, end = import_idx[0], import_idx[-1]
    block = lines[start : end + 1]
    for imp in new_import_lines:
        if imp not in block:
            block.append(imp)
    lines[start : end + 1] = sorted(block)
    return "\n".join(lines)


# A ktlint (standard:max-line-length, default 140) violation is judged purely by the actual
# rendered length, not by sentence shape, and the actual length depends on the domain name's
# length — a short name (Coupon) is forced onto a single line (standard:class-signature/
# argument-list-wrapping also enforce "collapse it if it fits on one line"), while a long name
# (LoyaltyCategory) must be wrapped argument-by-argument. So neither case can always be made
# correct with a static template string — the actual length must be computed and branched on
# at generation time.
def _call_expr(callee: str, args: list[str], indent: str = "") -> str:
    """Render a `callee(args)` call on one line if it fits, otherwise wrap each argument onto its own line."""
    one_line = f"{indent}{callee}({', '.join(args)})"
    if len(one_line) <= 140:
        return one_line
    arg_lines = "\n".join(f"{indent}    {a}," for a in args)
    return f"{indent}{callee}(\n{arg_lines}\n{indent})"


def _class_with_supertype_call(class_name: str, callee: str, args: list[str]) -> str:
    one_line = f"class {class_name} : {callee}({', '.join(args)})"
    if len(one_line) <= 140:
        return one_line
    return f"class {class_name} :\n{_call_expr(callee, args, indent='    ')}"


@dataclass(frozen=True)
class Names:
    Domain: str  # PascalCase singular (e.g. LoyaltyCategory)
    domain: str  # camelCase singular (e.g. loyaltyCategory)
    domains: str  # camelCase plural (e.g. loyaltyCategories)
    Domains: str  # PascalCase plural (e.g. LoyaltyCategories)
    domains_kebab: str  # kebab plural for the REST path (e.g. loyalty-categories)
    domain_scream: str  # SCREAMING_SNAKE singular (e.g. LOYALTY_CATEGORY)
    table_snake: str  # DB table name, snake_case plural (e.g. loyalty_categories)
    package: str  # lowercase package segment, no separator (e.g. loyaltycategory) — Java/Kotlin convention


def build_names(raw: str) -> Names:
    Domain = to_pascal(raw)
    domain = to_camel(Domain)
    domains = naive_pluralize(domain)
    Domains = to_pascal(domains)
    # Kebab-casing must be done on Domains, which has already been correctly pluralized. If we
    # instead just stripped the hyphen from domainKebab and appended 's' (e.g.
    # loyalty-category -> loyaltycategorys), the word boundary and pluralization rule would
    # both break — the same pitfall actually hit in the nestjs generator, so we compute it in
    # the order Domains -> kebab from the start.
    domains_kebab = to_kebab(Domains)
    return Names(
        Domain=Domain,
        domain=domain,
        domains=domains,
        Domains=Domains,
        domains_kebab=domains_kebab,
        domain_scream=to_scream(Domain),
        table_snake=to_snake(Domains),
        package=Domain.lower(),
    )


# ---------------------------------------------------------------------------
# File contents
# ---------------------------------------------------------------------------


def generate_files(n: Names) -> dict[str, str]:
    files: dict[str, str] = {}
    p = n.package
    D = n.Domain
    d = n.domain

    # ---- domain/ ----
    files[f"{p}/domain/{D}Status.kt"] = f"""package com.example.accountservice.{p}.domain

enum class {D}Status {{ PENDING, ACTIVE, CANCELLED }}
"""

    files[f"{p}/domain/{D}ErrorCode.kt"] = f"""package com.example.accountservice.{p}.domain

enum class {D}ErrorCode {{
    {n.domain_scream}_NOT_FOUND,
    {n.domain_scream}_ACTIVATION_REQUIRES_PENDING_STATUS,
    {n.domain_scream}_ALREADY_CANCELLED,
}}
"""

    files[f"{p}/domain/{D}Exception.kt"] = f"""package com.example.accountservice.{p}.domain

sealed class {D}Exception(
    message: String,
    val code: {D}ErrorCode,
) : RuntimeException(message)

class {D}NotFoundException(
    {d}Id: String,
) : {_call_expr(f"{D}Exception", [f'"{d} not found: ${d}Id"', f"{D}ErrorCode.{n.domain_scream}_NOT_FOUND"])}

{_class_with_supertype_call(
        f"{D}ActivationRequiresPendingStatusException",
        f"{D}Exception",
        [f'"Only a {D} in PENDING status can be activated."', f"{D}ErrorCode.{n.domain_scream}_ACTIVATION_REQUIRES_PENDING_STATUS"],
    )}

{_class_with_supertype_call(
        f"{D}AlreadyCancelledException",
        f"{D}Exception",
        [f'"This {D} has already been cancelled."', f"{D}ErrorCode.{n.domain_scream}_ALREADY_CANCELLED"],
    )}
"""

    files[f"{p}/domain/{D}Cancelled.kt"] = f"""package com.example.accountservice.{p}.domain

import java.time.LocalDateTime

/** Domain Event published when a {D} is cancelled (via the Outbox, see domain-events.md). */
data class {D}Cancelled(
    val {d}Id: String,
    val reason: String,
    val cancelledAt: LocalDateTime,
)
"""

    files[f"{p}/domain/{D}.kt"] = f"""package com.example.accountservice.{p}.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * {D} Aggregate Root — a pure Kotlin object with no dependency on any framework/ORM.
 * Persistence mapping is handled entirely by infrastructure/persistence/{D}JpaEntity + {D}Mapper
 * (the same domain/JPA separation structure as account/domain/Account.kt, card/domain/Card.kt).
 */
class {D} private constructor() {{
    var {d}Id: String = ""
        private set

    var ownerId: String = ""
        private set

    var status: {D}Status = {D}Status.PENDING
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    private val domainEvents: MutableList<Any> = mutableListOf()

    companion object {{
        /** Creates a new {D} — always starts in PENDING status and publishes no Domain Event. */
        fun create(ownerId: String): {D} =
            {D}().apply {{
                this.{d}Id = generateId()
                this.ownerId = ownerId
                this.status = {D}Status.PENDING
                this.createdAt = LocalDateTime.now()
            }}

        /**
         * Used by the Repository implementation to restore a {D} from persisted data (e.g. a
         * JPA entity). Unlike create(), it reconstructs the stored state as-is, without
         * generating a new identifier or Domain Event.
         */
        fun reconstitute(
            {d}Id: String,
            ownerId: String,
            status: {D}Status,
            createdAt: LocalDateTime,
        ): {D} =
            {D}().apply {{
                this.{d}Id = {d}Id
                this.ownerId = ownerId
                this.status = status
                this.createdAt = createdAt
            }}
    }}

    // Example of a simple state transition that publishes no Domain Event — a change that
    // needs no event just changes the state like this.
    fun activate() {{
        if (status != {D}Status.PENDING) throw {D}ActivationRequiresPendingStatusException()
        status = {D}Status.ACTIVE
    }}

    // Example of a state transition that publishes a Domain Event.
    fun cancel(reason: String) {{
        if (status == {D}Status.CANCELLED) throw {D}AlreadyCancelledException()
        status = {D}Status.CANCELLED
        domainEvents += {D}Cancelled({d}Id, reason, LocalDateTime.now())
    }}

    fun pullDomainEvents(): List<Any> = domainEvents.toList().also {{ domainEvents.clear() }}
}}
"""

    files[f"{p}/domain/{D}Repository.kt"] = f"""package com.example.accountservice.{p}.domain

/**
 * {D} write-model port — the interface the Command Service depends on.
 *
 * Read-only queries are split out into
 * [com.example.accountservice.{p}.application.query.{D}Query] (cqrs-pattern.md). The actual
 * implementation ({D}RepositoryImpl) implements both interfaces, but each Service is only
 * ever injected with the interface type it needs.
 */
interface {D}Repository {{
    fun find{n.Domains}(query: {D}FindQuery): List<{D}>

    fun save{D}({d}: {D})
}}

data class {D}FindQuery(
    val {d}Id: String? = null,
    val ownerId: String? = null,
)
"""

    # ---- application/command/ ----
    files[f"{p}/application/command/Create{D}Command.kt"] = f"""package com.example.accountservice.{p}.application.command

data class Create{D}Command(
    val ownerId: String,
)
"""

    files[f"{p}/application/command/Create{D}Result.kt"] = f"""package com.example.accountservice.{p}.application.command

import java.time.LocalDateTime

data class Create{D}Result(
    val {d}Id: String,
    val ownerId: String,
    val status: String,
    val createdAt: LocalDateTime,
)
"""

    files[f"{p}/application/command/Create{D}Service.kt"] = f"""package com.example.accountservice.{p}.application.command

import com.example.accountservice.{p}.domain.{D}
import com.example.accountservice.{p}.domain.{D}Repository
import org.springframework.stereotype.Service

// Since create() publishes no Domain Event (see {D}.kt above), this Service has no dependency
// on any Outbox-drain call — only a Command that publishes an event, like cancel, drains the
// Outbox right after saving (domain-events.md).
@Service
class Create{D}Service(
    private val {d}Repository: {D}Repository,
) {{
    fun create(command: Create{D}Command): Create{D}Result {{
        val {d} = {D}.create(command.ownerId)
        {d}Repository.save{D}({d})
        return Create{D}Result(
            {d}Id = {d}.{d}Id,
            ownerId = {d}.ownerId,
            status = {d}.status.name,
            createdAt = {d}.createdAt,
        )
    }}
}}
"""

    files[f"{p}/application/command/Cancel{D}Command.kt"] = f"""package com.example.accountservice.{p}.application.command

data class Cancel{D}Command(
    val {d}Id: String,
    val ownerId: String,
    val reason: String,
)
"""

    files[f"{p}/application/command/Cancel{D}Service.kt"] = f"""package com.example.accountservice.{p}.application.command

import com.example.accountservice.{p}.domain.{D}FindQuery
import com.example.accountservice.{p}.domain.{D}NotFoundException
import com.example.accountservice.{p}.domain.{D}Repository
import org.springframework.stereotype.Service

@Service
class Cancel{D}Service(
    private val {d}Repository: {D}Repository,
) {{
    fun cancel(command: Cancel{D}Command) {{
        val {d} =
            {d}Repository
                .find{n.Domains}({D}FindQuery({d}Id = command.{d}Id, ownerId = command.ownerId))
                .firstOrNull() ?: throw {D}NotFoundException(command.{d}Id)
        {d}.cancel(command.reason)
        {d}Repository.save{D}({d})
        // Returns immediately after saving — publishing/consuming from the Outbox to the
        // queue is handled independently by OutboxPoller/OutboxConsumer (no synchronous
        // drain allowed, domain-events.md).
    }}
}}
"""

    # ---- application/query/ ----
    files[f"{p}/application/query/{D}Query.kt"] = f"""package com.example.accountservice.{p}.application.query

import com.example.accountservice.{p}.domain.{D}
import com.example.accountservice.{p}.domain.{D}FindQuery

/**
 * Read-only port — the narrow interface the Query Service depends on.
 *
 * Follows the `<Domain>Query` naming/placement (application/query/) prescribed by the root
 * cqrs-pattern.md. Kept separate from the write model
 * ([com.example.accountservice.{p}.domain.{D}Repository]) so the compiler enforces that the
 * Query Service can never reach a write method like save.
 *
 * Queries reuse exactly the same signature (`find{n.Domains}`) as {D}Repository (the write
 * model) — the root repository-pattern.md's find<Noun>s naming convention is applied to the
 * Query port as well. There's no dedicated method for a single-record lookup; it's handled with
 * `{D}FindQuery({d}Id = ..., ownerId = ...)` + `.firstOrNull()`.
 */
interface {D}Query {{
    fun find{n.Domains}(query: {D}FindQuery): List<{D}>
}}
"""

    files[f"{p}/application/query/Get{D}Result.kt"] = f"""package com.example.accountservice.{p}.application.query

import java.time.LocalDateTime

data class Get{D}Result(
    val {d}Id: String,
    val ownerId: String,
    val status: String,
    val createdAt: LocalDateTime,
)
"""

    files[f"{p}/application/query/Get{D}Service.kt"] = f"""package com.example.accountservice.{p}.application.query

import com.example.accountservice.{p}.domain.{D}FindQuery
import com.example.accountservice.{p}.domain.{D}NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class Get{D}Service(
    private val {d}Query: {D}Query,
) {{
    fun get{D}(
        {d}Id: String,
        ownerId: String,
    ): Get{D}Result {{
        val {d} =
            {d}Query.find{n.Domains}({D}FindQuery({d}Id = {d}Id, ownerId = ownerId)).firstOrNull()
                ?: throw {D}NotFoundException({d}Id)
        return Get{D}Result(
            {d}Id = {d}.{d}Id,
            ownerId = {d}.ownerId,
            status = {d}.status.name,
            createdAt = {d}.createdAt,
        )
    }}
}}
"""

    # ---- application/event/ ----
    files[f"{p}/application/event/{D}CancelledEventHandler.kt"] = f"""package com.example.accountservice.{p}.application.event

import com.example.accountservice.{p}.domain.{D}Cancelled
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The follow-up processing point executed once {D}Cancelled is delivered via the Outbox
 * (notifications, publishing an Integration Event to another BC, etc). At the scaffolding
 * stage it only logs — implement the actual follow-up processing inside this class once it's
 * needed (see account/application/event/AccountSuspendedEventHandler.kt).
 */
@Component
class {D}CancelledEventHandler {{
    private val logger = LoggerFactory.getLogger({D}CancelledEventHandler::class.java)

    fun handle(event: {D}Cancelled) {{
        logger
            .atInfo()
            .addKeyValue("{d}_id", event.{d}Id)
            .addKeyValue("reason", event.reason)
            .log("{D} cancelled")
    }}
}}
"""

    # ---- infrastructure/persistence/ ----
    files[f"{p}/infrastructure/persistence/{D}JpaEntity.kt"] = f"""package com.example.accountservice.{p}.infrastructure.persistence

import com.example.accountservice.{p}.domain.{D}Status
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * The JPA-mapping-only counterpart of {p}/domain/{D}.kt.
 * The Domain Aggregate ({D}) knows nothing about this class at all — conversion is handled
 * entirely by {D}Mapper.
 *
 * Properties are declared as `var` with default values so the kotlin-jpa plugin can generate
 * the no-arg constructor Hibernate requires, and so {D}Mapper can overwrite the mutable fields
 * of an existing row (preserving its PK) in place on update.
 */
@Entity
@Table(name = "{n.table_snake}")
class {D}JpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var {d}Id: String = "",
    @Column(nullable = false)
    var ownerId: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: {D}Status = {D}Status.PENDING,
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
"""

    files[f"{p}/infrastructure/persistence/{D}JpaRepository.kt"] = f"""package com.example.accountservice.{p}.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface {D}JpaRepository : JpaRepository<{D}JpaEntity, Long> {{
    fun findBy{D}Id({d}Id: String): {D}JpaEntity?

    fun findBy{D}IdAndOwnerId(
        {d}Id: String,
        ownerId: String,
    ): {D}JpaEntity?
}}
"""

    files[f"{p}/infrastructure/persistence/{D}Mapper.kt"] = f"""package com.example.accountservice.{p}.infrastructure.persistence

import com.example.accountservice.{p}.domain.{D}

/**
 * An object dedicated to converting between {D} (pure domain) <-> {D}JpaEntity (JPA mapping).
 * Used only inside {D}RepositoryImpl — the Domain/Application layers know nothing about this object.
 */
internal object {D}Mapper {{
    fun toDomain(entity: {D}JpaEntity): {D} =
        {D}.reconstitute(
            {d}Id = entity.{d}Id,
            ownerId = entity.ownerId,
            status = entity.status,
            createdAt = entity.createdAt,
        )

    /** Creates a new entity (no PK, an insert target) for a brand-new {D}. */
    fun toNewEntity({d}: {D}): {D}JpaEntity =
        {D}JpaEntity(
            id = null,
            {d}Id = {d}.{d}Id,
            ownerId = {d}.ownerId,
            status = {d}.status,
            createdAt = {d}.createdAt,
        )

    /** Applies the domain {D}'s latest state (status) onto an existing entity (preserving its PK) — an update target. */
    fun updateEntity(
        entity: {D}JpaEntity,
        {d}: {D},
    ): {D}JpaEntity {{
        entity.status = {d}.status
        return entity
    }}
}}
"""

    files[f"{p}/infrastructure/persistence/{D}RepositoryImpl.kt"] = f"""package com.example.accountservice.{p}.infrastructure.persistence

import com.example.accountservice.{p}.application.query.{D}Query
import com.example.accountservice.{p}.domain.{D}
import com.example.accountservice.{p}.domain.{D}FindQuery
import com.example.accountservice.{p}.domain.{D}Repository
import com.example.accountservice.outbox.OutboxWriter
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * An implementation that implements both the {D} write model ([{D}Repository]) and read model
 * ([{D}Query]) together. Since each Service is only ever injected with the interface type it
 * needs, the Query Service can never reach save.
 */
@Repository
class {D}RepositoryImpl(
    private val jpaRepository: {D}JpaRepository,
    private val outboxWriter: OutboxWriter,
) : {D}Repository,
    {D}Query {{
    // The lookup target is always narrowed down to a single {d}Id (including the
    // {d}Id+ownerId combination) — add pagination (page/take) to this method once a list
    // query is needed (see api-response.md).
    override fun find{n.Domains}(query: {D}FindQuery): List<{D}> {{
        val entity =
            if (query.{d}Id != null && query.ownerId != null) {{
                jpaRepository.findBy{D}IdAndOwnerId(query.{d}Id, query.ownerId)
            }} else if (query.{d}Id != null) {{
                jpaRepository.findBy{D}Id(query.{d}Id)
            }} else {{
                null
            }}
        return entity?.let {{ listOf({D}Mapper.toDomain(it)) }} ?: emptyList()
    }}

    @Transactional
    override fun save{D}({d}: {D}) {{
        val entity =
            jpaRepository
                .findBy{D}Id({d}.{d}Id)
                ?.let {{ {D}Mapper.updateEntity(it, {d}) }}
                ?: {D}Mapper.toNewEntity({d})
        jpaRepository.save(entity)
        // Commits the Aggregate state and the Outbox row in the same transaction — this
        // prevents the event from being saved without the Aggregate state (dual-write), or
        // conversely being lost (domain-events.md).
        outboxWriter.saveAll({d}.pullDomainEvents())
    }}
}}
"""

    # ---- interfaces/rest/ ----
    files[f"{p}/interfaces/rest/Cancel{D}Request.kt"] = f"""package com.example.accountservice.{p}.interfaces.rest

import jakarta.validation.constraints.NotBlank

data class Cancel{D}Request(
    @field:NotBlank
    val reason: String,
)
"""

    files[f"{p}/interfaces/rest/{D}Controller.kt"] = f"""package com.example.accountservice.{p}.interfaces.rest

import com.example.accountservice.{p}.application.command.Cancel{D}Command
import com.example.accountservice.{p}.application.command.Cancel{D}Service
import com.example.accountservice.{p}.application.command.Create{D}Command
import com.example.accountservice.{p}.application.command.Create{D}Result
import com.example.accountservice.{p}.application.command.Create{D}Service
import com.example.accountservice.{p}.application.query.Get{D}Result
import com.example.accountservice.{p}.application.query.Get{D}Service
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/{n.domains_kebab}")
class {D}Controller(
    private val create{D}Service: Create{D}Service,
    private val cancel{D}Service: Cancel{D}Service,
    private val get{D}Service: Get{D}Service,
) {{
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create{D}(authentication: Authentication): Create{D}Result =
        create{D}Service.create(Create{D}Command(authentication.name))

    @PostMapping("/{{{d}Id}}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel{D}(
        authentication: Authentication,
        @PathVariable {d}Id: String,
        @Valid @RequestBody request: Cancel{D}Request,
    ) {{
        cancel{D}Service.cancel(Cancel{D}Command({d}Id, authentication.name, request.reason))
    }}

    @GetMapping("/{{{d}Id}}")
    fun get{D}(
        authentication: Authentication,
        @PathVariable {d}Id: String,
    ): Get{D}Result = get{D}Service.get{D}({d}Id, authentication.name)
}}
"""

    return files


# ---------------------------------------------------------------------------
# Flyway migration
# ---------------------------------------------------------------------------


def next_migration_path(migration_dir: Path, n: Names) -> Path:
    existing = sorted(migration_dir.glob("V*__*.sql"))
    max_version = 0
    for f in existing:
        m = re.match(r"V(\d+)__", f.name)
        if m:
            max_version = max(max_version, int(m.group(1)))
    return migration_dir / f"V{max_version + 1}__create_{n.table_snake}.sql"


def migration_sql(n: Names) -> str:
    id_col = f"{to_snake(n.Domain)}_id"
    return f"""CREATE TABLE {n.table_snake} (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    {id_col} VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'CANCELLED')),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_{n.table_snake}_{id_col} UNIQUE ({id_col})
);

CREATE INDEX idx_{n.table_snake}_owner_id ON {n.table_snake} (owner_id);
"""


# ---------------------------------------------------------------------------
# Auto-patching EventHandlerRegistry.kt / GlobalExceptionHandler.kt (--wire)
# ---------------------------------------------------------------------------


def wire_outbox_relay(path: Path, n: Names) -> str:
    """Registers the new domain's Cancelled event handler in EventHandlerRegistry.kt.

    outbox/EventHandlerRegistry.kt routes events via a `Map<eventType, handler>` literal built
    at constructor-execution time — this function patches to match that structure. Because this
    file explicitly injects each individual handler via the constructor rather than Spring's
    automatic List<Interface> collection (see domain-events.md), it must be edited directly for
    every new domain.
    """
    if not path.is_file():
        return f"Warning: could not find {path}, skipping automatic EventHandlerRegistry wiring."
    content = path.read_text(encoding="utf-8")

    D = n.Domain
    d = n.domain
    p = n.package
    handler_import = f"import com.example.accountservice.{p}.application.event.{D}CancelledEventHandler"
    event_import = f"import com.example.accountservice.{p}.domain.{D}Cancelled"

    if handler_import in content:
        return f"{D}CancelledEventHandler is already registered in EventHandlerRegistry.kt — skipping."

    if "\nimport " not in f"\n{content}":
        return f"Warning: could not find an import block in {path}, skipping automatic EventHandlerRegistry wiring."
    content = _insert_imports_sorted(content, [event_import, handler_import])

    # Add the new parameter right before the constructor's last parameter (the comma-terminated accountIntegrationEventController line).
    ctor_match = re.search(r"(class EventHandlerRegistry\s*\(\n)([\s\S]*?)(\n\)\s*\{)", content)
    if not ctor_match:
        return f"Warning: could not find the EventHandlerRegistry constructor in {path}, skipping parameter addition."
    new_param = f"    private val {d}CancelledEventHandler: {D}CancelledEventHandler,"
    ctor_body = ctor_match.group(2)
    new_ctor_body = f"{ctor_body}\n{new_param}"
    content = content[: ctor_match.start(2)] + new_ctor_body + content[ctor_match.end(2) :]

    # Add the new mapping after the last entry of handlers = mapOf(...), right before its closing ")".
    # Anchor: the point where the `fun registeredEventTypes()` declaration follows right after the
    # ")" that closes mapOf(...) — the one fixed location in this file (only the last entry of the
    # mapOf block changes as domains are added). Anchored on the function signature rather than its
    # KDoc comment text so this regex doesn't depend on the comment's exact (translatable) wording.
    map_close_match = re.search(r"(\n)(        \)\n\n(?:    /\*\*(?:.|\n)*?\*/\n)?    fun registeredEventTypes)", content)
    if not map_close_match:
        return f"Warning: could not find the handlers = mapOf(...) block in {path}, skipping mapping addition."
    new_entry = (
        f'            "{D}Cancelled" to {{ _, payload ->\n'
        f"                {d}CancelledEventHandler.handle(objectMapper.readValue(payload, {D}Cancelled::class.java))\n"
        "            },\n"
    )
    insert_at = map_close_match.start(2)
    content = content[:insert_at] + new_entry + content[insert_at:]

    path.write_text(content, encoding="utf-8")
    return f"Registered {D}CancelledEventHandler in EventHandlerRegistry.kt: {path}"


def wire_global_exception_handler(path: Path, n: Names) -> str:
    """Adds a new-domain-exception -> HTTP-response mapping to GlobalExceptionHandler.kt."""
    if not path.is_file():
        return f"Warning: could not find {path}, skipping automatic GlobalExceptionHandler wiring."
    content = path.read_text(encoding="utf-8")

    D = n.Domain
    p = n.package
    not_found_import = f"import com.example.accountservice.{p}.domain.{D}NotFoundException"
    exception_import = f"import com.example.accountservice.{p}.domain.{D}Exception"

    if not_found_import in content:
        return f"{D}NotFoundException is already registered in GlobalExceptionHandler.kt — skipping."

    if "\nimport " not in f"\n{content}":
        return f"Warning: could not find an import block in {path}, skipping automatic GlobalExceptionHandler wiring."
    content = _insert_imports_sorted(content, [exception_import, not_found_import])

    # Insert the two new handlers right before the MethodArgumentNotValidException handler — a
    # fixed anchor right after this class's last domain-exception handler (CardException) and
    # before the common validation/uncaught-exception handlers, so the insertion point stays
    # stable as domains are added.
    anchor = "    @ExceptionHandler(MethodArgumentNotValidException::class)"
    if anchor not in content:
        return f"Warning: could not find the insertion anchor in {path}, skipping handler addition."
    new_handlers = f"""    @ExceptionHandler({D}NotFoundException::class)
    fun handle{D}NotFound(e: {D}NotFoundException): ResponseEntity<ErrorResponse> {{
        logger.warn("{D} not found: {{}}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }}

    @ExceptionHandler({D}Exception::class)
    fun handle{D}Exception(e: {D}Exception): ResponseEntity<ErrorResponse> {{
        logger.warn("{D} request failed: {{}}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }}

{anchor}"""
    content = content.replace(anchor, new_handlers, 1)

    path.write_text(content, encoding="utf-8")
    return f"Registered {D}Exception handling in GlobalExceptionHandler.kt: {path}"


def print_manual_snippets(n: Names) -> None:
    D = n.Domain
    d = n.domain
    p = n.package
    print("")
    print("--- Content to add manually to EventHandlerRegistry.kt (not auto-applied since --wire was not given) ---")
    print(f"import com.example.accountservice.{p}.application.event.{D}CancelledEventHandler")
    print(f"import com.example.accountservice.{p}.domain.{D}Cancelled")
    print(f"  // Add to the constructor parameters: private val {d}CancelledEventHandler: {D}CancelledEventHandler,")
    print(f'  // Add inside handlers = mapOf(...): "{D}Cancelled" to {{ _, payload -> {d}CancelledEventHandler.handle(...) }}')
    print("")
    print("--- Content to add manually to GlobalExceptionHandler.kt ---")
    print(f"import com.example.accountservice.{p}.domain.{D}Exception")
    print(f"import com.example.accountservice.{p}.domain.{D}NotFoundException")
    print(f"  // Add the methods @ExceptionHandler({D}NotFoundException::class) / @ExceptionHandler({D}Exception::class)")
    print("")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="kotlin-springboot new domain scaffolding generator")
    parser.add_argument("domain_name", help="PascalCase domain name (e.g. Coupon, LoyaltyCategory)")
    parser.add_argument(
        "--project-root",
        default=None,
        help="Gradle module root (containing src/main/kotlin, src/main/resources). Default: scripts/../examples",
    )
    parser.add_argument(
        "--wire",
        action="store_true",
        help="Auto-patch EventHandlerRegistry.kt / GlobalExceptionHandler.kt (default: only print the snippets)",
    )
    args = parser.parse_args()

    if args.domain_name.startswith("-"):
        parser.error("The domain name must be given as the first argument.")

    script_dir = Path(__file__).resolve().parent
    project_root = Path(args.project_root).resolve() if args.project_root else (script_dir / ".." / "examples").resolve()

    n = build_names(args.domain_name)

    kotlin_root = project_root / "src" / "main" / "kotlin" / "com" / "example" / "accountservice"
    migration_dir = project_root / "src" / "main" / "resources" / "db" / "migration"

    if not kotlin_root.is_dir():
        print(f"Error: {kotlin_root} does not exist (check that project-root is correct).", file=sys.stderr)
        sys.exit(1)

    files = generate_files(n)
    for rel_path, content in files.items():
        target = kotlin_root / rel_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    print(f"{n.Domain} domain generated: {kotlin_root / n.package}/ ({len(files)} files)")
    print(f"REST path: /{n.domains_kebab} (POST to create, GET/{{{n.domain}Id}} to look up, POST /{{{n.domain}Id}}/cancel to cancel)")
    print("")
    print("Note: a naive pluralization rule (+s / +es / consonant+y -> ies) was used — for a domain")
    print(f"  with an irregular plural, you may need to manually adjust find{n.Domains}/{n.domains}, etc.")

    if migration_dir.is_dir():
        migration_path = next_migration_path(migration_dir, n)
        migration_path.write_text(migration_sql(n), encoding="utf-8")
        print(f"Flyway migration generated: {migration_path}")
    else:
        print(f"Warning: {migration_dir} does not exist, skipping Flyway migration generation.", file=sys.stderr)

    if args.wire:
        print(wire_outbox_relay(kotlin_root / "outbox" / "EventHandlerRegistry.kt", n))
        print(wire_global_exception_handler(kotlin_root / "common" / "GlobalExceptionHandler.kt", n))
    else:
        print_manual_snippets(n)

    print("Next: verify with bash harness.sh <projectRoot>, then run ./gradlew ktlintFormat && ./gradlew build.")


if __name__ == "__main__":
    main()
