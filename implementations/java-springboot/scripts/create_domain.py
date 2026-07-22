#!/usr/bin/env python3
"""New domain scaffolding generator (Java Spring Boot).

This turns the "practical implementation template" defined by
docs/reference.md/repository-pattern.md/cqrs-pattern.md/domain-events.md into
actual code that passes the harness (all rules), then generalizes it into a
reusable form by parameterizing only the domain name. It combines Card's
(2nd domain, no events) domain/JPA separation structure with Account's
Repository/Outbox pattern to generate a minimal Aggregate
(create/activate/cancel(reason)) whose single status field cycles
PENDING -> ACTIVE/CANCELLED. Only cancel() publishes a domain event.

Unlike nestjs's create-domain.js, there is no "module registration" step —
Spring automatically collects @Service/@Component/@Repository/@RestController
across the whole classpath (component scanning), so the new domain's
OutboxEventHandler implementation is also auto-injected by OutboxConsumer.
In other words, this generator only needs to create the files correctly; there
is no step to find and patch a central wiring file (there is no place in
AccountServiceApplication.java that lists domain beans — verified directly).

Usage:
    python3 scripts/create_domain.py <PascalCaseDomainName> [--out <projectRoot>] [--package <basePackage>]

Examples:
    python3 scripts/create_domain.py Coupon
        -> generates under ../examples/src/main/java/com/example/accountservice/coupon/ (default target)
    python3 scripts/create_domain.py LoyaltyCategory --out /tmp/scratch-app
        -> generates under /tmp/scratch-app/src/main/java/com/example/accountservice/loyaltycategory/
           (--out is a project root with the same layout as examples/ — a directory containing src/main/...)

After generating, verify:
    cd <projectRoot> && ./gradlew spotlessApply && ./gradlew build
    bash harness.sh <projectRoot>
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DEFAULT_BASE_PACKAGE = "com.example.accountservice"


# ---------------------------------------------------------------------------
# Name conversion
# ---------------------------------------------------------------------------


def to_pascal(raw: str) -> str:
    if not raw:
        raise ValueError("Domain name is empty.")
    return raw[0].upper() + raw[1:]


def to_camel(pascal: str) -> str:
    return pascal[0].lower() + pascal[1:]


def split_words(pascal: str) -> list[str]:
    # PascalCase -> word list. Consecutive uppercase letters (an acronym) are grouped as one word.
    return re.findall(r"[A-Z]+(?=[A-Z][a-z]|\b)|[A-Z][a-z0-9]*|[a-z0-9]+", pascal)


def to_snake(pascal: str) -> str:
    return "_".join(w.lower() for w in split_words(pascal))


def to_kebab(pascal: str) -> str:
    return "-".join(w.lower() for w in split_words(pascal))


def to_scream_snake(pascal: str) -> str:
    return "_".join(w.upper() for w in split_words(pascal))


# A very simple rule-based pluralizer — irregular plurals (e.g. Category -> Categories)
# must be fixed by hand after generation; the run output notes this (same policy as
# nestjs's create-domain.js).
def naive_pluralize(camel: str) -> str:
    if re.search(r"[sxz]$|[cs]h$", camel):
        return camel + "es"
    if re.search(r"[^aeiouAEIOU]y$", camel) and len(camel) > 1:
        return camel[:-1] + "ies"
    return camel + "s"


class Names:
    def __init__(self, raw_domain_name: str, base_package: str):
        Domain = to_pascal(raw_domain_name)
        domain = to_camel(Domain)
        domains_camel = naive_pluralize(domain)
        Domains = to_pascal(domains_camel)
        # kebab/snake are derived again from the already-correctly-pluralized Domains —
        # appending 's' directly to the kebab-case string (loyalty-category ->
        # loyalty-categorys) breaks both word boundaries and the pluralization rule
        # (the same bug hit in nestjs's create-domain.js for the same reason).
        domains_kebab = to_kebab(Domains)
        domains_snake = to_snake(Domains)
        domain_snake = to_snake(Domain)
        DOMAIN_SCREAM = to_scream_snake(Domain)
        pkg = Domain.lower()  # Java package segment — lowercase, no separators (idiomatic)

        self.Domain = Domain
        self.domain = domain
        self.Domains = Domains
        self.domains = domains_camel
        self.domains_kebab = domains_kebab
        self.domains_snake = domains_snake
        self.domain_id_snake = f"{domain_snake}_id"
        self.uk_constraint = f"uk_{domains_snake}_{domain_snake}_id"
        self.idx_owner = f"idx_{domains_snake}_owner_id"
        self.pkg = pkg
        self.basepkg = base_package
        self.err_not_found = f"{DOMAIN_SCREAM}_NOT_FOUND"
        self.err_activation_requires_pending = (
            f"{DOMAIN_SCREAM}_ACTIVATION_REQUIRES_PENDING_STATUS"
        )
        self.err_already_cancelled = f"{DOMAIN_SCREAM}_ALREADY_CANCELLED"
        # Whether naive_pluralize actually applied a rule other than +s — for the advisory message
        self.plural_is_irregular = domains_camel != domain + "s"

    def tokens(self) -> dict[str, str]:
        return {
            "__basepkg__": self.basepkg,
            "__pkg__": self.pkg,
            "__Domain__": self.Domain,
            "__domain__": self.domain,
            "__Domains__": self.Domains,
            "__domains__": self.domains,
            "__domains_kebab__": self.domains_kebab,
            "__domains_snake__": self.domains_snake,
            "__domain_id_snake__": self.domain_id_snake,
            "__uk_constraint__": self.uk_constraint,
            "__idx_owner__": self.idx_owner,
            "__ERR_NOT_FOUND__": self.err_not_found,
            "__ERR_ACTIVATION_REQUIRES_PENDING__": self.err_activation_requires_pending,
            "__ERR_ALREADY_CANCELLED__": self.err_already_cancelled,
        }


def render(template: str, tokens: dict[str, str]) -> str:
    out = template
    for token, value in tokens.items():
        out = out.replace(token, value)
    return out


# ---------------------------------------------------------------------------
# File templates (paths are relative to the basePackage directory)
# ---------------------------------------------------------------------------

TEMPLATES: dict[str, str] = {}

TEMPLATES["domain/__Domain__Status.java"] = """package __basepkg__.__pkg__.domain;

public enum __Domain__Status {
    PENDING,
    ACTIVE,
    CANCELLED
}
"""

TEMPLATES["domain/__Domain__CancelledEvent.java"] = """package __basepkg__.__pkg__.domain;

import java.time.LocalDateTime;

public record __Domain__CancelledEvent(String __domain__Id, String reason, LocalDateTime cancelledAt) {}
"""

TEMPLATES["domain/__Domain__Exception.java"] = """package __basepkg__.__pkg__.domain;

public class __Domain__Exception extends RuntimeException {

    public enum ErrorCode {
        __ERR_NOT_FOUND__,
        __ERR_ACTIVATION_REQUIRES_PENDING__,
        __ERR_ALREADY_CANCELLED__
    }

    private final ErrorCode code;

    public __Domain__Exception(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
"""

TEMPLATES["domain/__Domain__FindQuery.java"] = """package __basepkg__.__pkg__.domain;

import java.util.List;

public record __Domain__FindQuery(
        int page, int take, String __domain__Id, String ownerId, List<String> status) {}
"""

TEMPLATES["domain/__Domains__WithCount.java"] = """package __basepkg__.__pkg__.domain;

import java.util.List;

/**
 * Result of a {@code find__Domains__} query — returns the list together with the total count.
 * A single-record lookup also reuses this type: call with {@code __Domain__FindQuery.take} set to
 * 1 and take the first result from {@code __domains__()} (see repository-pattern.md).
 */
public record __Domains__WithCount(List<__Domain__> __domains__, long count) {}
"""

TEMPLATES["domain/__Domain__Repository.java"] = """package __basepkg__.__pkg__.domain;

/**
 * The write-side Repository contract for the __Domain__ Aggregate (owned by domain). Read-only
 * queries are separated into a distinct application/query/__Domain__Query interface (see
 * cqrs-pattern.md).
 */
public interface __Domain__Repository {
    __Domains__WithCount find__Domains__(__Domain__FindQuery query);

    void save__Domain__(__Domain__ __domain__);
}
"""

TEMPLATES["domain/__Domain__.java"] = """package __basepkg__.__pkg__.domain;

import __basepkg__.common.IdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * __Domain__ Aggregate Root — a pure domain object. It does not depend on any framework/ORM.
 * Persistence mapping is handled entirely by
 * infrastructure/persistence/__Domain__JpaEntity + __Domain__Mapper (the same domain/JPA
 * separation as account/domain/Account.java).
 */
public class __Domain__ {

    private String __domain__Id;
    private String ownerId;
    private __Domain__Status status;
    private LocalDateTime createdAt;

    private final List<Object> domainEvents = new ArrayList<>();

    private __Domain__() {}

    /**
     * Used by the Repository implementation to restore a __Domain__ from persisted data (a JPA
     * entity, etc). Unlike create(), it does not create a new identifier/status — it reconstructs
     * the saved state as-is.
     */
    public static __Domain__ reconstitute(
            String __domain__Id, String ownerId, __Domain__Status status, LocalDateTime createdAt) {
        __Domain__ __domain__ = new __Domain__();
        __domain__.__domain__Id = __domain__Id;
        __domain__.ownerId = ownerId;
        __domain__.status = status;
        __domain__.createdAt = createdAt;
        return __domain__;
    }

    public static __Domain__ create(String ownerId) {
        __Domain__ __domain__ = new __Domain__();
        __domain__.__domain__Id = IdGenerator.generate();
        __domain__.ownerId = ownerId;
        __domain__.status = __Domain__Status.PENDING;
        __domain__.createdAt = LocalDateTime.now();
        return __domain__;
    }

    // An example of a simple state transition that publishes no event — a change that needs no
    // domain event just changes the status like this.
    public void activate() {
        if (this.status != __Domain__Status.PENDING) {
            throw new __Domain__Exception(
                    __Domain__Exception.ErrorCode.__ERR_ACTIVATION_REQUIRES_PENDING__,
                    "Only a pending __Domain__ can be activated.");
        }
        this.status = __Domain__Status.ACTIVE;
    }

    // An example of a state transition that publishes an event.
    public void cancel(String reason) {
        if (this.status == __Domain__Status.CANCELLED) {
            throw new __Domain__Exception(
                    __Domain__Exception.ErrorCode.__ERR_ALREADY_CANCELLED__, "The __Domain__ is already cancelled.");
        }
        this.status = __Domain__Status.CANCELLED;
        this.domainEvents.add(new __Domain__CancelledEvent(this.__domain__Id, reason, LocalDateTime.now()));
    }

    /** Called by the Repository implementation right after saving to pull and clear unpublished events (see domain-events.md). */
    public List<Object> pullDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    public String get__Domain__Id() {
        return __domain__Id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public __Domain__Status getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
"""

# ---- application/command ----

TEMPLATES["application/command/Create__Domain__Command.java"] = """package __basepkg__.__pkg__.application.command;

public record Create__Domain__Command(String ownerId) {}
"""

TEMPLATES["application/command/Create__Domain__Result.java"] = """package __basepkg__.__pkg__.application.command;

import java.time.LocalDateTime;

public record Create__Domain__Result(
        String __domain__Id, String ownerId, String status, LocalDateTime createdAt) {}
"""

TEMPLATES["application/command/Create__Domain__Service.java"] = """package __basepkg__.__pkg__.application.command;

import __basepkg__.__pkg__.domain.__Domain__;
import __basepkg__.__pkg__.domain.__Domain__Repository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Create__Domain__Service {

    private final __Domain__Repository __domain__Repository;

    public Create__Domain__Result create(Create__Domain__Command command) {
        __Domain__ __domain__ = __Domain__.create(command.ownerId());
        __domain__Repository.save__Domain__(__domain__);
        return new Create__Domain__Result(
                __domain__.get__Domain__Id(),
                __domain__.getOwnerId(),
                __domain__.getStatus().name(),
                __domain__.getCreatedAt());
    }
}
"""

TEMPLATES["application/command/Activate__Domain__Command.java"] = """package __basepkg__.__pkg__.application.command;

public record Activate__Domain__Command(String __domain__Id, String requesterId) {}
"""

TEMPLATES["application/command/Activate__Domain__Service.java"] = """package __basepkg__.__pkg__.application.command;

import __basepkg__.__pkg__.domain.__Domain__;
import __basepkg__.__pkg__.domain.__Domain__Exception;
import __basepkg__.__pkg__.domain.__Domain__FindQuery;
import __basepkg__.__pkg__.domain.__Domain__Repository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Activate__Domain__Service {

    private final __Domain__Repository __domain__Repository;

    public void activate(Activate__Domain__Command command) {
        __Domain__ __domain__ =
                __domain__Repository
                        .find__Domains__(
                                new __Domain__FindQuery(0, 1, command.__domain__Id(), command.requesterId(), null))
                        .__domains__()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new __Domain__Exception(
                                                __Domain__Exception.ErrorCode.__ERR_NOT_FOUND__,
                                                "__Domain__ not found."));
        __domain__.activate();
        __domain__Repository.save__Domain__(__domain__);
    }
}
"""

TEMPLATES["application/command/Cancel__Domain__Command.java"] = """package __basepkg__.__pkg__.application.command;

public record Cancel__Domain__Command(String __domain__Id, String requesterId, String reason) {}
"""

TEMPLATES["application/command/Cancel__Domain__Service.java"] = """package __basepkg__.__pkg__.application.command;

import __basepkg__.__pkg__.domain.__Domain__;
import __basepkg__.__pkg__.domain.__Domain__Exception;
import __basepkg__.__pkg__.domain.__Domain__FindQuery;
import __basepkg__.__pkg__.domain.__Domain__Repository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Cancel__Domain__Service {

    private final __Domain__Repository __domain__Repository;

    public void cancel(Cancel__Domain__Command command) {
        __Domain__ __domain__ =
                __domain__Repository
                        .find__Domains__(
                                new __Domain__FindQuery(0, 1, command.__domain__Id(), command.requesterId(), null))
                        .__domains__()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new __Domain__Exception(
                                                __Domain__Exception.ErrorCode.__ERR_NOT_FOUND__,
                                                "__Domain__ not found."));
        __domain__.cancel(command.reason());
        __domain__Repository.save__Domain__(__domain__);
        // Outbox → SQS publish/consume is handled independently by OutboxPoller/OutboxConsumer
        // (see domain-events.md) — the Command Service returns right after saving.
    }
}
"""

# ---- application/query ----

TEMPLATES["application/query/__Domain__Query.java"] = """package __basepkg__.__pkg__.application.query;

import __basepkg__.__pkg__.domain.__Domain__FindQuery;
import __basepkg__.__pkg__.domain.__Domains__WithCount;

/**
 * A read-only interface dedicated to the Query Service. A narrow contract separate from the
 * write-side {@code __Domain__Repository} (domain). The Query Service must depend only on this
 * interface — it exposes no write methods (see cqrs-pattern.md).
 */
public interface __Domain__Query {
    __Domains__WithCount find__Domains__(__Domain__FindQuery query);
}
"""

TEMPLATES["application/query/Get__Domain__Result.java"] = """package __basepkg__.__pkg__.application.query;

import java.time.LocalDateTime;

public record Get__Domain__Result(
        String __domain__Id, String ownerId, String status, LocalDateTime createdAt) {}
"""

TEMPLATES["application/query/Get__Domain__Service.java"] = """package __basepkg__.__pkg__.application.query;

import __basepkg__.__pkg__.domain.__Domain__;
import __basepkg__.__pkg__.domain.__Domain__Exception;
import __basepkg__.__pkg__.domain.__Domain__FindQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class Get__Domain__Service {

    private final __Domain__Query __domain__Query;

    public Get__Domain__Result get__Domain__(String __domain__Id, String requesterId) {
        __Domain__ __domain__ =
                __domain__Query
                        .find__Domains__(new __Domain__FindQuery(0, 1, __domain__Id, requesterId, null))
                        .__domains__()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new __Domain__Exception(
                                                __Domain__Exception.ErrorCode.__ERR_NOT_FOUND__,
                                                "__Domain__ not found."));
        return new Get__Domain__Result(
                __domain__.get__Domain__Id(),
                __domain__.getOwnerId(),
                __domain__.getStatus().name(),
                __domain__.getCreatedAt());
    }
}
"""

# ---- application/event ----

TEMPLATES["application/event/__Domain__CancelledEventHandler.java"] = """package __basepkg__.__pkg__.application.event;

import __basepkg__.__pkg__.domain.__Domain__CancelledEvent;
import __basepkg__.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Processes a {@link __Domain__CancelledEvent} (an internal Domain Event) accumulated in the
 * Outbox. {@code OutboxConsumer} automatically collects every {@link OutboxEventHandler}
 * implementation on the classpath, so when this event is received from SQS, this handler is
 * invoked — no separate manual registration is needed (see module-pattern.md for why there is no
 * module-registration file).
 *
 * <p>At the scaffolding stage this only logs — actual follow-up processing (notifications,
 * publishing an Integration Event to another BC, etc) should be implemented here once the domain
 * requirements are settled (see account/application/event/AccountSuspendedEventHandler.java).
 */
@Component
@RequiredArgsConstructor
public class __Domain__CancelledEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(__Domain__CancelledEventHandler.class);

    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return __Domain__CancelledEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        __Domain__CancelledEvent event = objectMapper.readValue(payload, __Domain__CancelledEvent.class);
        log.info(
                "__Domain__ cancelled - __domain__Id={}, reason={}", event.__domain__Id(), event.reason());
    }
}
"""

# ---- infrastructure/persistence ----

TEMPLATES["infrastructure/persistence/__Domain__JpaEntity.java"] = """package __basepkg__.__pkg__.infrastructure.persistence;

import __basepkg__.__pkg__.domain.__Domain__Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The JPA-mapping-only counterpart of __pkg__/domain/__Domain__.java. The Domain Aggregate
 * (__Domain__) knows nothing about this class at all — conversion is handled entirely by
 * __Domain__Mapper (the same structure as account/infrastructure/persistence/AccountJpaEntity, see
 * layer-architecture.md).
 */
@Entity
@Table(name = "__domains_snake__")
public class __Domain__JpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String __domain__Id;

    @Column(nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private __Domain__Status status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected __Domain__JpaEntity() {}

    __Domain__JpaEntity(
            Long id, String __domain__Id, String ownerId, __Domain__Status status, LocalDateTime createdAt) {
        this.id = id;
        this.__domain__Id = __domain__Id;
        this.ownerId = ownerId;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Applies the domain __Domain__'s latest state to an existing row (preserving id) — used to save a status transition. */
    void applyMutableState(__Domain__Status status) {
        this.status = status;
    }

    Long getId() {
        return id;
    }

    String get__Domain__Id() {
        return __domain__Id;
    }

    String getOwnerId() {
        return ownerId;
    }

    __Domain__Status getStatus() {
        return status;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
"""

TEMPLATES["infrastructure/persistence/__Domain__Mapper.java"] = """package __basepkg__.__pkg__.infrastructure.persistence;

import __basepkg__.__pkg__.domain.__Domain__;

/**
 * A class dedicated to converting between __Domain__ (pure domain) and __Domain__JpaEntity (JPA
 * mapping). Used only inside __Domain__RepositoryImpl — the Domain/Application layers know nothing
 * about this class.
 */
final class __Domain__Mapper {

    private __Domain__Mapper() {}

    static __Domain__ toDomain(__Domain__JpaEntity entity) {
        return __Domain__.reconstitute(
                entity.get__Domain__Id(), entity.getOwnerId(), entity.getStatus(), entity.getCreatedAt());
    }

    /** Creates a new entity for a new __Domain__ (no PK, to be inserted). */
    static __Domain__JpaEntity toNewEntity(__Domain__ __domain__) {
        return new __Domain__JpaEntity(
                null,
                __domain__.get__Domain__Id(),
                __domain__.getOwnerId(),
                __domain__.getStatus(),
                __domain__.getCreatedAt());
    }

    /** Applies the domain __Domain__'s latest state to an existing entity (preserving PK) — to be updated. */
    static __Domain__JpaEntity updateEntity(__Domain__JpaEntity entity, __Domain__ __domain__) {
        entity.applyMutableState(__domain__.getStatus());
        return entity;
    }
}
"""

TEMPLATES["infrastructure/persistence/__Domain__JpaRepository.java"] = """package __basepkg__.__pkg__.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface __Domain__JpaRepository extends JpaRepository<__Domain__JpaEntity, Long> {
    Optional<__Domain__JpaEntity> findBy__Domain__Id(String __domain__Id);
}
"""

TEMPLATES["infrastructure/persistence/__Domain__RepositoryImpl.java"] = """package __basepkg__.__pkg__.infrastructure.persistence;

import __basepkg__.__pkg__.application.query.__Domain__Query;
import __basepkg__.__pkg__.domain.__Domain__;
import __basepkg__.__pkg__.domain.__Domain__FindQuery;
import __basepkg__.__pkg__.domain.__Domain__Repository;
import __basepkg__.__pkg__.domain.__Domain__Status;
import __basepkg__.__pkg__.domain.__Domains__WithCount;
import __basepkg__.outbox.OutboxWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements both the write-side {@link __Domain__Repository} and the read-side {@link
 * __Domain__Query} for __Domain__ in a single class (the same structure as
 * account/infrastructure/persistence/AccountRepositoryImpl). Each Application layer is injected
 * only with the narrow interface it needs (Repository or Query).
 */
@Repository
@RequiredArgsConstructor
public class __Domain__RepositoryImpl implements __Domain__Repository, __Domain__Query {

    private final __Domain__JpaRepository jpaRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    @Override
    public __Domains__WithCount find__Domains__(__Domain__FindQuery query) {
        String listJpql = buildJpql(query, false);
        var listQuery =
                em.createQuery(listJpql, __Domain__JpaEntity.class)
                        .setFirstResult(query.page() * query.take())
                        .setMaxResults(query.take());
        applyParams(listQuery, query);
        List<__Domain__> __domains__ =
                listQuery.getResultList().stream().map(__Domain__Mapper::toDomain).toList();

        String countJpql = buildJpql(query, true);
        var countQuery = em.createQuery(countJpql, Long.class);
        applyParams(countQuery, query);
        long count = countQuery.getSingleResult();

        return new __Domains__WithCount(__domains__, count);
    }

    @Override
    @Transactional
    public void save__Domain__(__Domain__ __domain__) {
        __Domain__JpaEntity entity =
                jpaRepository
                        .findBy__Domain__Id(__domain__.get__Domain__Id())
                        .map(existing -> __Domain__Mapper.updateEntity(existing, __domain__))
                        .orElseGet(() -> __Domain__Mapper.toNewEntity(__domain__));
        jpaRepository.save(entity);
        // Records events to the Outbox inside the same physical transaction as the Aggregate save (see domain-events.md).
        outboxWriter.saveAll(__domain__.pullDomainEvents());
    }

    private String buildJpql(__Domain__FindQuery query, boolean count) {
        StringBuilder sb =
                new StringBuilder(count ? "SELECT COUNT(x) FROM __Domain__JpaEntity x" : "SELECT x FROM __Domain__JpaEntity x");
        boolean where = false;
        if (query.__domain__Id() != null && !query.__domain__Id().isBlank()) {
            sb.append(where ? " AND" : " WHERE").append(" x.__domain__Id = :__domain__Id");
            where = true;
        }
        if (query.ownerId() != null && !query.ownerId().isBlank()) {
            sb.append(where ? " AND" : " WHERE").append(" x.ownerId = :ownerId");
            where = true;
        }
        if (query.status() != null && !query.status().isEmpty()) {
            sb.append(where ? " AND" : " WHERE").append(" x.status IN :status");
        }
        if (!count) sb.append(" ORDER BY x.__domain__Id DESC");
        return sb.toString();
    }

    private void applyParams(Query q, __Domain__FindQuery query) {
        if (query.__domain__Id() != null && !query.__domain__Id().isBlank())
            q.setParameter("__domain__Id", query.__domain__Id());
        if (query.ownerId() != null && !query.ownerId().isBlank())
            q.setParameter("ownerId", query.ownerId());
        if (query.status() != null && !query.status().isEmpty()) {
            q.setParameter("status", query.status().stream().map(__Domain__Status::valueOf).toList());
        }
    }
}
"""

# ---- interfaces/rest ----

TEMPLATES["interfaces/rest/Cancel__Domain__Request.java"] = """package __basepkg__.__pkg__.interfaces.rest;

import jakarta.validation.constraints.NotBlank;

public record Cancel__Domain__Request(@NotBlank String reason) {}
"""

TEMPLATES["interfaces/rest/__Domain__Controller.java"] = """package __basepkg__.__pkg__.interfaces.rest;

import static net.logstash.logback.argument.StructuredArguments.kv;

import __basepkg__.account.interfaces.rest.ErrorResponse;
import __basepkg__.__pkg__.application.command.Activate__Domain__Command;
import __basepkg__.__pkg__.application.command.Activate__Domain__Service;
import __basepkg__.__pkg__.application.command.Cancel__Domain__Command;
import __basepkg__.__pkg__.application.command.Cancel__Domain__Service;
import __basepkg__.__pkg__.application.command.Create__Domain__Command;
import __basepkg__.__pkg__.application.command.Create__Domain__Result;
import __basepkg__.__pkg__.application.command.Create__Domain__Service;
import __basepkg__.__pkg__.application.query.Get__Domain__Result;
import __basepkg__.__pkg__.application.query.Get__Domain__Service;
import __basepkg__.__pkg__.domain.__Domain__Exception;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/__domains_kebab__")
@RequiredArgsConstructor
public class __Domain__Controller {

    private static final Logger log = LoggerFactory.getLogger(__Domain__Controller.class);

    private final Create__Domain__Service create__Domain__Service;
    private final Activate__Domain__Service activate__Domain__Service;
    private final Cancel__Domain__Service cancel__Domain__Service;
    private final Get__Domain__Service get__Domain__Service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Create__Domain__Result create__Domain__(Authentication authentication) {
        String ownerId = authentication.getName();
        return create__Domain__Service.create(new Create__Domain__Command(ownerId));
    }

    @PostMapping("/{__domain__Id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate__Domain__(Authentication authentication, @PathVariable String __domain__Id) {
        activate__Domain__Service.activate(
                new Activate__Domain__Command(__domain__Id, authentication.getName()));
    }

    @PostMapping("/{__domain__Id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel__Domain__(
            Authentication authentication,
            @PathVariable String __domain__Id,
            @Valid @RequestBody Cancel__Domain__Request request) {
        cancel__Domain__Service.cancel(
                new Cancel__Domain__Command(__domain__Id, authentication.getName(), request.reason()));
    }

    @GetMapping("/{__domain__Id}")
    public Get__Domain__Result get__Domain__(
            Authentication authentication, @PathVariable String __domain__Id) {
        return get__Domain__Service.get__Domain__(__domain__Id, authentication.getName());
    }

    @ExceptionHandler(__Domain__Exception.class)
    public ResponseEntity<ErrorResponse> handle__Domain__Exception(__Domain__Exception e) {
        HttpStatus status =
                e.code() == __Domain__Exception.ErrorCode.__ERR_NOT_FOUND__
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.BAD_REQUEST;
        log.warn("__Domain__ request failed", kv("code", e.code()), kv("message", e.getMessage()));
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }
}
"""

MIGRATION_TEMPLATE = """CREATE TABLE __domains_snake__ (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    __domain_id_snake__ VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'CANCELLED')),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT __uk_constraint__ UNIQUE (__domain_id_snake__)
);

CREATE INDEX __idx_owner__ ON __domains_snake__ (owner_id);
"""


# ---------------------------------------------------------------------------
# Generation logic
# ---------------------------------------------------------------------------


def next_migration_number(migration_dir: Path) -> int:
    max_n = 0
    if migration_dir.is_dir():
        for f in migration_dir.glob("V*__*.sql"):
            m = re.match(r"V(\d+)__", f.name)
            if m:
                max_n = max(max_n, int(m.group(1)))
    return max_n + 1


def generate(raw_domain_name: str, project_root: Path, base_package: str) -> None:
    names = Names(raw_domain_name, base_package)
    tokens = names.tokens()

    package_path = Path(*base_package.split(".")) / names.pkg
    java_root = project_root / "src" / "main" / "java" / package_path
    migration_dir = project_root / "src" / "main" / "resources" / "db" / "migration"

    written: list[Path] = []
    for rel_path, template in TEMPLATES.items():
        # rel_path itself contains tokens too (e.g. __Domain__.java), so it must be rendered to
        # produce the actual file name.
        target = java_root / render(rel_path, tokens)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(render(template, tokens), encoding="utf-8")
        written.append(target)

    migration_dir.mkdir(parents=True, exist_ok=True)
    n = next_migration_number(migration_dir)
    migration_name = f"V{n}__create_{names.domains_snake}.sql"
    migration_path = migration_dir / migration_name
    migration_path.write_text(render(MIGRATION_TEMPLATE, tokens), encoding="utf-8")
    written.append(migration_path)

    print(f"{names.Domain} domain generated: {java_root} ({len(written)} files)")
    print(
        f"REST paths: /{names.domains_kebab} "
        f"(POST to create, POST /:{{{names.domain}Id}}/activate to activate, "
        f"POST /:{{{names.domain}Id}}/cancel to cancel, GET /:{{{names.domain}Id}} to fetch)"
    )
    print(f"Flyway migration: {migration_path}")
    print()
    if names.plural_is_irregular:
        print(
            f"Note: the y->ies rule was applied instead of the naive pluralization rule "
            f"({names.domain} -> {names.domains}). If this is an irregular plural (e.g. person -> people), "
            f"you may need to manually adjust generated names such as "
            f"find{names.Domains}/{names.domains}/{names.domains_kebab}."
        )
    else:
        print(
            "Note: the naive pluralization rule (+s / +es / y->ies) was used — for an irregular "
            f"plural domain, you may need to manually adjust names such as find{names.Domains}/{names.domains}."
        )
    print()
    print(
        "No module-registration step: Spring automatically collects "
        "@Service/@Component/@Repository/@RestController across the whole classpath "
        "(component scanning) — the OutboxEventHandler implementation is also auto-injected by "
        "OutboxConsumer. There is no central wiring file to fix by hand."
    )
    print()
    print(f"Next: cd {project_root} && ./gradlew spotlessApply && ./gradlew build")
    print(f"      bash harness.sh {project_root}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="New domain scaffolding generator (Java Spring Boot)"
    )
    parser.add_argument("domain_name", help="PascalCase domain name (e.g. Coupon, LoyaltyCategory)")
    parser.add_argument(
        "--out",
        dest="out",
        default=None,
        help="Target project root (a directory containing src/main/...). Default: ../examples",
    )
    parser.add_argument(
        "--package",
        dest="package",
        default=DEFAULT_BASE_PACKAGE,
        help=f"Base package (default: {DEFAULT_BASE_PACKAGE})",
    )
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    project_root = Path(args.out).resolve() if args.out else (script_dir / ".." / "examples").resolve()

    generate(args.domain_name, project_root, args.package)


if __name__ == "__main__":
    main()
