#!/usr/bin/env python3
"""새 도메인 스캐폴딩 생성기 (kotlin-springboot).

docs/reference.md의 실전 구현 템플릿(Account/Card 예시)을 그대로 따르는 최소 Aggregate
(단일 status 필드, PENDING -> ACTIVE/CANCELLED) + Command/Query Service(CQRS, Handler/
CommandBus 없음) + 도메인 이벤트 1종(cancel()에서만 발행) + Repository + REST Controller
+ DTO + Flyway 마이그레이션까지 한 번에 생성한다.

nestjs의 scripts/create-domain.js와 설계 철학은 같지만(도메인 이름만 파라미터로 뽑아
재사용), kotlin-springboot는 Spring 컴포넌트 스캔(@Service/@Component/@RestController/
@Repository는 classpath 전체에서 자동 수집됨) 덕분에 nestjs의 `@Module({ providers: [...] })`
같은 중앙 등록 파일이 아예 없다 — 새 도메인 파일만 올바른 위치/애노테이션으로 생성하면
Spring이 알아서 찾는다.

**예외 2가지**: OutboxRelay(outbox/OutboxRelay.kt)와 GlobalExceptionHandler
(common/GlobalExceptionHandler.kt)는 둘 다 "공유 인프라이지만 생성자에 개별 핸들러를
명시적으로 주입받는" 구조라(Spring의 List<Interface> 자동 수집 패턴이 아님) 새 도메인마다
이 두 파일을 직접 고쳐야 한다. --wire 옵션이 이 두 파일에 대한 패치를 자동 적용한다
(옵션을 주지 않으면 붙여넣을 스니펫만 콘솔에 출력 — 기존 파일을 스크립트가 임의로 고치는 걸
원치 않을 수 있어 기본값은 안전한 쪽).

사용법:
  python3 scripts/create-domain.py <PascalCaseDomainName> [--project-root <gradle 모듈 루트>] [--wire]

예:
  python3 scripts/create-domain.py Coupon
    -> ../examples/src/main/kotlin/com/example/accountservice/coupon/ 아래에 생성(기본 대상)
  python3 scripts/create-domain.py LoyaltyCategory --project-root /tmp/scratch-app --wire
    -> 지정한 프로젝트 루트(Gradle 모듈, src/main/kotlin·src/main/resources를 포함) 아래 생성 +
       OutboxRelay.kt/GlobalExceptionHandler.kt 자동 패치 + Flyway 마이그레이션 추가

--wire를 주지 않으면 OutboxRelay.kt/GlobalExceptionHandler.kt는 건드리지 않고, 붙여넣을
내용만 콘솔에 출력한다. Flyway 마이그레이션은 추가 전용(기존 파일을 고치지 않음)이라
--wire 여부와 무관하게 항상 생성한다.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

# ---------------------------------------------------------------------------
# 이름 변환
# ---------------------------------------------------------------------------


def to_pascal(raw: str) -> str:
    if not raw:
        raise ValueError("도메인 이름이 비어 있습니다.")
    return raw[0].upper() + raw[1:]


def to_camel(raw: str) -> str:
    if not raw:
        raise ValueError("도메인 이름이 비어 있습니다.")
    return raw[0].lower() + raw[1:]


_BOUNDARY = re.compile(r"([a-z0-9])([A-Z])")


def to_kebab(pascal: str) -> str:
    return _BOUNDARY.sub(r"\1-\2", pascal).lower()


def to_snake(pascal: str) -> str:
    return _BOUNDARY.sub(r"\1_\2", pascal).lower()


def to_scream(pascal: str) -> str:
    return _BOUNDARY.sub(r"\1_\2", pascal).upper()


# 아주 단순한 규칙 기반 복수형 — 불규칙 복수형은 생성 후 수동으로 고쳐야 한다.
# nestjs의 naivePluralize와 동일한 규칙(+s / +es / 자음+y -> ies).
def naive_pluralize(word: str) -> str:
    if re.search(r"[sxz]$", word) or re.search(r"[cs]h$", word):
        return f"{word}es"
    if re.search(r"[^aeiouAEIOU]y$", word):
        return f"{word[:-1]}ies"
    return f"{word}s"


def _insert_imports_sorted(content: str, new_import_lines: list[str]) -> str:
    """새 import 줄들을 기존 import 블록에 삽입하고, 전체 블록을 사전순으로 재정렬한다.

    단순히 import 블록 끝에 덧붙이기만 하면 ktlint(standard:import-ordering, "사전순 정렬,
    사이에 빈 줄 없이"를 요구)가 실패한다 — OutboxRelay.kt/GlobalExceptionHandler.kt에는
    java/javax/kotlin/별칭 import가 전혀 없으므로 단순 문자열 정렬로 충분하다.
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


# ktlint(standard:max-line-length, 기본 140) 위반은 문장 형태가 아니라 렌더링된 실제 길이로만
# 판정되고, 실제 길이는 도메인 이름 길이에 따라 달라진다 — 짧은 이름(Coupon)에서는 한 줄로 붙는 게
# 강제되고(standard:class-signature/argument-list-wrapping이 "한 줄에 들어가면 붙여라"도 강제한다),
# 긴 이름(LoyaltyCategory)에서는 인자 단위로 줄바꿈해야 한다. 그래서 두 케이스 모두 정적 템플릿
# 문자열로는 항상 옳게 만들 수 없고, 생성 시점에 실제 길이를 계산해 분기해야 한다.
def _call_expr(callee: str, args: list[str], indent: str = "") -> str:
    """`callee(args)` 호출을 한 줄에 맞으면 한 줄로, 안 맞으면 인자별 줄바꿈으로 렌더링한다."""
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
    Domain: str  # PascalCase 단수 (예: LoyaltyCategory)
    domain: str  # camelCase 단수 (예: loyaltyCategory)
    domains: str  # camelCase 복수 (예: loyaltyCategories)
    Domains: str  # PascalCase 복수 (예: LoyaltyCategories)
    domains_kebab: str  # REST 경로용 kebab 복수 (예: loyalty-categories)
    domain_scream: str  # SCREAMING_SNAKE 단수 (예: LOYALTY_CATEGORY)
    table_snake: str  # DB 테이블명 snake_case 복수 (예: loyalty_categories)
    package: str  # 소문자 패키지 세그먼트, 구분자 없음(예: loyaltycategory) — Java/Kotlin 관례


def build_names(raw: str) -> Names:
    Domain = to_pascal(raw)
    domain = to_camel(Domain)
    domains = naive_pluralize(domain)
    Domains = to_pascal(domains)
    # kebab화는 이미 올바르게 복수화한 Domains에 대해 수행해야 한다. domainKebab에
    # 하이픈만 지우고 's'를 붙이면(예: loyalty-category -> loyaltycategorys처럼) 단어
    # 경계가 깨지고 복수형 규칙도 깨진다 — nestjs 생성기에서 실제로 겪은 버그와 동일한
    # 함정이라 처음부터 Domains -> kebab 순서로 계산한다.
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
# 파일 콘텐츠
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
        [f'"PENDING 상태의 {D}만 활성화할 수 있습니다."', f"{D}ErrorCode.{n.domain_scream}_ACTIVATION_REQUIRES_PENDING_STATUS"],
    )}

{_class_with_supertype_call(
        f"{D}AlreadyCancelledException",
        f"{D}Exception",
        [f'"이미 취소된 {D}입니다."', f"{D}ErrorCode.{n.domain_scream}_ALREADY_CANCELLED"],
    )}
"""

    files[f"{p}/domain/{D}Cancelled.kt"] = f"""package com.example.accountservice.{p}.domain

import java.time.LocalDateTime

/** {D}이(가) 취소되었을 때 발행되는 Domain Event (Outbox 경유, domain-events.md 참고). */
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
 * {D} Aggregate Root — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin 객체.
 * 영속성 매핑은 infrastructure/persistence/{D}JpaEntity + {D}Mapper가 전담한다
 * (account/domain/Account.kt, card/domain/Card.kt와 동일한 domain/JPA 분리 구조).
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
        /** 새 {D}을(를) 생성한다 — 항상 PENDING 상태로 시작하며 Domain Event는 발행하지 않는다. */
        fun create(ownerId: String): {D} =
            {D}().apply {{
                this.{d}Id = generateId()
                this.ownerId = ownerId
                this.status = {D}Status.PENDING
                this.createdAt = LocalDateTime.now()
            }}

        /**
         * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 {D}을(를) 복원할 때 사용한다.
         * create()와 달리 새 식별자·Domain Event를 만들지 않고 저장된 상태를 그대로 재구성한다.
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

    // Domain Event를 발행하지 않는 단순 상태 전이 예시 — 이벤트가 필요 없는 변경은 이렇게 상태만 바꾼다.
    fun activate() {{
        if (status != {D}Status.PENDING) throw {D}ActivationRequiresPendingStatusException()
        status = {D}Status.ACTIVE
    }}

    // Domain Event를 발행하는 상태 전이 예시.
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
 * {D} 쓰기 모델 포트 — Command Service가 의존하는 인터페이스.
 *
 * 읽기 전용 조회는 [com.example.accountservice.{p}.application.query.{D}Query]로 분리한다
 * (cqrs-pattern.md). 실제 구현체({D}RepositoryImpl)는 두 인터페이스를 모두 구현하지만,
 * 각 Service는 자신에게 필요한 인터페이스 타입으로만 주입받는다.
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

// create()는 Domain Event를 발행하지 않으므로(위 {D}.kt 참고) 이 Service는 Outbox 드레인 호출에
// 의존하지 않는다 — cancel처럼 이벤트를 발행하는 Command만 저장 직후 Outbox를 드레인한다(domain-events.md).
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
import com.example.accountservice.outbox.OutboxRelay
import org.springframework.stereotype.Service

@Service
class Cancel{D}Service(
    private val {d}Repository: {D}Repository,
    private val outboxRelay: OutboxRelay,
) {{
    fun cancel(command: Cancel{D}Command) {{
        val {d} =
            {d}Repository
                .find{n.Domains}({D}FindQuery({d}Id = command.{d}Id, ownerId = command.ownerId))
                .firstOrNull() ?: throw {D}NotFoundException(command.{d}Id)
        {d}.cancel(command.reason)
        {d}Repository.save{D}({d})
        outboxRelay.processPending()
    }}
}}
"""

    # ---- application/query/ ----
    files[f"{p}/application/query/{D}Query.kt"] = f"""package com.example.accountservice.{p}.application.query

import com.example.accountservice.{p}.domain.{D}

/**
 * 읽기 전용 포트 — Query Service가 의존하는 좁은 인터페이스.
 *
 * root cqrs-pattern.md가 규정하는 `<Domain>Query` 네이밍/배치(application/query/)를 따른다.
 * 쓰기 모델([com.example.accountservice.{p}.domain.{D}Repository])과 분리해, Query Service가
 * save 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제한다.
 */
interface {D}Query {{
    fun findBy{D}IdAndOwnerId(
        {d}Id: String,
        ownerId: String,
    ): {D}?
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
            {d}Query.findBy{D}IdAndOwnerId({d}Id, ownerId)
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
 * {D}Cancelled가 Outbox를 통해 전달되면 실행되는 후속 처리 지점(알림, 다른 BC로의
 * Integration Event 발행 등). 스캐폴딩 단계에서는 로깅만 한다 — 실제 후속 처리가 필요해지면
 * 이 클래스 안에 구현한다(account/application/event/AccountSuspendedEventHandler.kt 참고).
 */
@Component
class {D}CancelledEventHandler {{
    private val logger = LoggerFactory.getLogger({D}CancelledEventHandler::class.java)

    fun handle(event: {D}Cancelled) {{
        logger
            .atInfo()
            .addKeyValue("{d}_id", event.{d}Id)
            .addKeyValue("reason", event.reason)
            .log("{D} 취소됨")
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
 * {p}/domain/{D}.kt의 JPA 매핑 전용 대응물.
 * Domain Aggregate({D})는 이 클래스를 전혀 알지 못한다 — 변환은 {D}Mapper가 전담한다.
 *
 * 프로퍼티를 `var` + 기본값으로 선언해 Hibernate가 요구하는 기본 생성자를 kotlin-jpa 플러그인이
 * 생성하게 하고, 갱신 시 {D}Mapper가 기존 행(PK 보존)의 가변 필드를 그대로 덮어쓸 수 있게 한다.
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
 * {D}(순수 도메인) <-> {D}JpaEntity(JPA 매핑) 변환 전담 오브젝트.
 * {D}RepositoryImpl 내부에서만 사용된다 — Domain/Application 레이어는 이 오브젝트를 알지 못한다.
 */
internal object {D}Mapper {{
    fun toDomain(entity: {D}JpaEntity): {D} =
        {D}.reconstitute(
            {d}Id = entity.{d}Id,
            ownerId = entity.ownerId,
            status = entity.status,
            createdAt = entity.createdAt,
        )

    /** 신규 {D}을(를) 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
    fun toNewEntity({d}: {D}): {D}JpaEntity =
        {D}JpaEntity(
            id = null,
            {d}Id = {d}.{d}Id,
            ownerId = {d}.ownerId,
            status = {d}.status,
            createdAt = {d}.createdAt,
        )

    /** 기존 엔티티(PK 보존)에 도메인 {D}의 최신 상태(status)를 반영한다 — update 대상. */
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
 * {D} 쓰기 모델([{D}Repository])과 읽기 모델([{D}Query])을 함께 구현하는 구현체.
 * 각 Service는 자신에게 필요한 인터페이스 타입으로만 주입받으므로, Query Service는 save에 접근할 수 없다.
 */
@Repository
class {D}RepositoryImpl(
    private val jpaRepository: {D}JpaRepository,
    private val outboxWriter: OutboxWriter,
) : {D}Repository,
    {D}Query {{
    // 조회 대상은 항상 {d}Id 하나로 좁혀진다({d}Id+ownerId 조합 포함) — 목록 조회가 필요해지면
    // 이 메서드에 페이지네이션(page/take)을 추가한다(api-response.md 참고).
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
        // Aggregate 상태와 Outbox 행을 같은 트랜잭션에 커밋한다 — 이벤트가 Aggregate 상태 없이
        // 저장되거나(dual-write), 반대로 유실되는 경우가 생기지 않는다(domain-events.md).
        outboxWriter.saveAll({d}.pullDomainEvents())
    }}

    override fun findBy{D}IdAndOwnerId(
        {d}Id: String,
        ownerId: String,
    ): {D}? =
        jpaRepository
            .findBy{D}IdAndOwnerId({d}Id, ownerId)
            ?.let({D}Mapper::toDomain)
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
# Flyway 마이그레이션
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
# OutboxRelay.kt / GlobalExceptionHandler.kt 자동 패치 (--wire)
# ---------------------------------------------------------------------------


def wire_outbox_relay(path: Path, n: Names) -> str:
    """OutboxRelay.kt에 새 도메인의 Cancelled 이벤트 핸들러를 등록한다.

    이 파일은 Spring의 List<Interface> 자동 수집이 아니라 생성자에 개별 핸들러를 명시적으로
    주입받는 구조라(domain-events.md 참고), 새 도메인마다 이 파일을 직접 고쳐야 한다.
    """
    if not path.is_file():
        return f"경고: {path} 를 찾지 못해 OutboxRelay 자동 wiring을 건너뜁니다."
    content = path.read_text(encoding="utf-8")

    D = n.Domain
    d = n.domain
    p = n.package
    handler_import = f"import com.example.accountservice.{p}.application.event.{D}CancelledEventHandler"
    event_import = f"import com.example.accountservice.{p}.domain.{D}Cancelled"

    if handler_import in content:
        return f"이미 OutboxRelay.kt에 {D}CancelledEventHandler가 등록돼 있어 건너뜁니다."

    if "\nimport " not in f"\n{content}":
        return f"경고: {path} 에서 import 블록을 찾지 못해 OutboxRelay 자동 wiring을 건너뜁니다."
    content = _insert_imports_sorted(content, [event_import, handler_import])

    # 생성자 마지막 파라미터(콤마로 끝나는 cardIntegrationEventController 줄) 앞에 새 파라미터 추가.
    ctor_match = re.search(r"(class OutboxRelay\s*\(\n)([\s\S]*?)(\n\)\s*\{)", content)
    if not ctor_match:
        return f"경고: {path} 에서 OutboxRelay 생성자를 찾지 못해 파라미터 추가를 건너뜁니다."
    new_param = f"    private val {d}CancelledEventHandler: {D}CancelledEventHandler,"
    ctor_body = ctor_match.group(2)
    new_ctor_body = f"{ctor_body}\n{new_param}"
    content = content[: ctor_match.start(2)] + new_ctor_body + content[ctor_match.end(2) :]

    # dispatch()의 when 블록 마지막 이벤트 분기(else 앞)에 새 분기를 추가한다.
    dispatch_match = re.search(r"(\n)(\s*else -> logger\.warn)", content)
    if not dispatch_match:
        return f"경고: {path} 에서 dispatch() when 블록을 찾지 못해 분기 추가를 건너뜁니다."
    new_branch = (
        f'            "{D}Cancelled" ->\n'
        f"                {d}CancelledEventHandler.handle(objectMapper.readValue(payload, {D}Cancelled::class.java))\n"
    )
    insert_at = dispatch_match.start(2)
    content = content[:insert_at] + new_branch + content[insert_at:]

    path.write_text(content, encoding="utf-8")
    return f"OutboxRelay.kt에 {D}CancelledEventHandler 등록 완료: {path}"


def wire_global_exception_handler(path: Path, n: Names) -> str:
    """GlobalExceptionHandler.kt에 새 도메인 예외 -> HTTP 응답 매핑을 추가한다."""
    if not path.is_file():
        return f"경고: {path} 를 찾지 못해 GlobalExceptionHandler 자동 wiring을 건너뜁니다."
    content = path.read_text(encoding="utf-8")

    D = n.Domain
    p = n.package
    not_found_import = f"import com.example.accountservice.{p}.domain.{D}NotFoundException"
    exception_import = f"import com.example.accountservice.{p}.domain.{D}Exception"

    if not_found_import in content:
        return f"이미 GlobalExceptionHandler.kt에 {D}NotFoundException이 등록돼 있어 건너뜁니다."

    if "\nimport " not in f"\n{content}":
        return f"경고: {path} 에서 import 블록을 찾지 못해 GlobalExceptionHandler 자동 wiring을 건너뜁니다."
    content = _insert_imports_sorted(content, [exception_import, not_found_import])

    # MethodArgumentNotValidException 핸들러 바로 앞에 새 핸들러 2개를 삽입한다 — 이 클래스의
    # 마지막 도메인 예외 핸들러(CardException) 다음, 공통 검증/미확인 예외 핸들러들보다 앞이라는
    # 고정된 앵커라 도메인이 늘어도 삽입 위치가 안정적이다.
    anchor = "    @ExceptionHandler(MethodArgumentNotValidException::class)"
    if anchor not in content:
        return f"경고: {path} 에서 삽입 앵커를 찾지 못해 핸들러 추가를 건너뜁니다."
    new_handlers = f"""    @ExceptionHandler({D}NotFoundException::class)
    fun handle{D}NotFound(e: {D}NotFoundException): ResponseEntity<ErrorResponse> {{
        logger.warn("{D}을(를) 찾을 수 없음: {{}}", e.message)
        return errorResponse(HttpStatus.NOT_FOUND, e.code.name, e.message ?: "")
    }}

    @ExceptionHandler({D}Exception::class)
    fun handle{D}Exception(e: {D}Exception): ResponseEntity<ErrorResponse> {{
        logger.warn("{D} 요청 실패: {{}}", e.message)
        return errorResponse(HttpStatus.BAD_REQUEST, e.code.name, e.message ?: "")
    }}

{anchor}"""
    content = content.replace(anchor, new_handlers, 1)

    path.write_text(content, encoding="utf-8")
    return f"GlobalExceptionHandler.kt에 {D}Exception 처리 등록 완료: {path}"


def print_manual_snippets(n: Names) -> None:
    D = n.Domain
    d = n.domain
    p = n.package
    print("")
    print("--- OutboxRelay.kt에 수동으로 추가할 내용 (--wire를 주지 않아 자동 적용 안 됨) ---")
    print(f"import com.example.accountservice.{p}.application.event.{D}CancelledEventHandler")
    print(f"import com.example.accountservice.{p}.domain.{D}Cancelled")
    print(f"  // 생성자 파라미터에 추가: private val {d}CancelledEventHandler: {D}CancelledEventHandler,")
    print(f'  // dispatch() when 블록에 추가: "{D}Cancelled" -> {d}CancelledEventHandler.handle(...)')
    print("")
    print("--- GlobalExceptionHandler.kt에 수동으로 추가할 내용 ---")
    print(f"import com.example.accountservice.{p}.domain.{D}Exception")
    print(f"import com.example.accountservice.{p}.domain.{D}NotFoundException")
    print(f"  // @ExceptionHandler({D}NotFoundException::class) / @ExceptionHandler({D}Exception::class) 메서드 추가")
    print("")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="kotlin-springboot 새 도메인 스캐폴딩 생성기")
    parser.add_argument("domain_name", help="PascalCase 도메인 이름 (예: Coupon, LoyaltyCategory)")
    parser.add_argument(
        "--project-root",
        default=None,
        help="Gradle 모듈 루트 (src/main/kotlin, src/main/resources를 포함). 기본값: scripts/../examples",
    )
    parser.add_argument(
        "--wire",
        action="store_true",
        help="OutboxRelay.kt / GlobalExceptionHandler.kt를 자동 패치한다 (기본값: 스니펫만 출력)",
    )
    args = parser.parse_args()

    if args.domain_name.startswith("-"):
        parser.error("도메인 이름을 첫 번째 인자로 주어야 합니다.")

    script_dir = Path(__file__).resolve().parent
    project_root = Path(args.project_root).resolve() if args.project_root else (script_dir / ".." / "examples").resolve()

    n = build_names(args.domain_name)

    kotlin_root = project_root / "src" / "main" / "kotlin" / "com" / "example" / "accountservice"
    migration_dir = project_root / "src" / "main" / "resources" / "db" / "migration"

    if not kotlin_root.is_dir():
        print(f"오류: {kotlin_root} 가 존재하지 않습니다 (project-root가 올바른지 확인).", file=sys.stderr)
        sys.exit(1)

    files = generate_files(n)
    for rel_path, content in files.items():
        target = kotlin_root / rel_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    print(f"{n.Domain} 도메인 생성 완료: {kotlin_root / n.package}/ ({len(files)}개 파일)")
    print(f"REST 경로: /{n.domains_kebab} (POST 생성, GET/{{{n.domain}Id}} 조회, POST /{{{n.domain}Id}}/cancel 취소)")
    print("")
    print("참고: 나이브 복수형 규칙(+s / +es / 자음+y -> ies)을 썼습니다 — 불규칙 복수형 도메인이면")
    print(f"  find{n.Domains}/{n.domains} 등을 수동으로 다듬어야 할 수 있습니다.")

    if migration_dir.is_dir():
        migration_path = next_migration_path(migration_dir, n)
        migration_path.write_text(migration_sql(n), encoding="utf-8")
        print(f"Flyway 마이그레이션 생성: {migration_path}")
    else:
        print(f"경고: {migration_dir} 가 존재하지 않아 Flyway 마이그레이션 생성을 건너뜁니다.", file=sys.stderr)

    if args.wire:
        print(wire_outbox_relay(kotlin_root / "outbox" / "OutboxRelay.kt", n))
        print(wire_global_exception_handler(kotlin_root / "common" / "GlobalExceptionHandler.kt", n))
    else:
        print_manual_snippets(n)

    print("다음: bash harness.sh <projectRoot>로 검증하고, ./gradlew ktlintFormat && ./gradlew build를 실행하세요.")


if __name__ == "__main__":
    main()
