#!/usr/bin/env python3
"""새 도메인 스캐폴딩 생성기 (Java Spring Boot).

docs/reference.md/repository-pattern.md/cqrs-pattern.md/domain-events.md가 정의하는
"실전 구현 템플릿"을 실제로 코드화해 harness(전체 규칙)를 통과시킨 뒤, 도메인 이름만
파라미터로 뽑아 재사용 가능하게 일반화한 것이다. Card(2번째 도메인, 이벤트 없음)의
domain/JPA 분리 구조 + Account의 Repository/Outbox 패턴을 조합해, 단일 status 필드가
PENDING -> ACTIVE/CANCELLED로 순환하는 최소 Aggregate(create/activate/cancel(reason))를
생성한다. cancel()만 도메인 이벤트 1종을 발행한다.

nestjs의 create-domain.js와 달리 "모듈 등록" 단계가 없다 — Spring은
@Service/@Component/@Repository/@RestController를 클래스패스 전체에서 자동으로
수집하므로(component scanning), 새 도메인의 OutboxEventHandler 구현체도
OutboxConsumer가 자동으로 주입받는다. 즉 이 생성기는 파일만 정확히 생성하면 되고,
central wiring 파일을 찾아 고치는 단계가 없다(AccountServiceApplication.java에는
domain bean을 나열하는 곳이 없음 — 직접 확인함).

사용법:
    python3 scripts/create_domain.py <PascalCaseDomainName> [--out <projectRoot>] [--package <basePackage>]

예:
    python3 scripts/create_domain.py Coupon
        -> ../examples/src/main/java/com/example/accountservice/coupon/ 아래에 생성(기본 대상)
    python3 scripts/create_domain.py LoyaltyCategory --out /tmp/scratch-app
        -> /tmp/scratch-app/src/main/java/com/example/accountservice/loyaltycategory/ 아래 생성
           (--out은 examples/와 같은 레이아웃의 프로젝트 루트 — src/main/... 를 포함하는 디렉터리)

생성 후 확인:
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
# 이름 변환
# ---------------------------------------------------------------------------


def to_pascal(raw: str) -> str:
    if not raw:
        raise ValueError("도메인 이름이 비어 있습니다.")
    return raw[0].upper() + raw[1:]


def to_camel(pascal: str) -> str:
    return pascal[0].lower() + pascal[1:]


def split_words(pascal: str) -> list[str]:
    # PascalCase -> 단어 목록. 연속 대문자(약어)는 하나의 단어로 묶는다.
    return re.findall(r"[A-Z]+(?=[A-Z][a-z]|\b)|[A-Z][a-z0-9]*|[a-z0-9]+", pascal)


def to_snake(pascal: str) -> str:
    return "_".join(w.lower() for w in split_words(pascal))


def to_kebab(pascal: str) -> str:
    return "-".join(w.lower() for w in split_words(pascal))


def to_scream_snake(pascal: str) -> str:
    return "_".join(w.upper() for w in split_words(pascal))


# 아주 단순한 규칙 기반 복수형 — 불규칙 복수형(예: Category -> Categories)은 생성 후
# 수동으로 고쳐야 한다는 걸 실행 결과에 안내한다(nestjs create-domain.js와 동일한 방침).
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
        # kebab/snake는 이미 올바르게 복수화한 Domains에서 다시 파생한다 — kebab-case
        # 문자열에 직접 's'를 붙이면(loyalty-category -> loyalty-categorys) 단어 경계와
        # 복수형 규칙이 모두 깨진다(nestjs create-domain.js에서 동일한 이유로 겪은 버그).
        domains_kebab = to_kebab(Domains)
        domains_snake = to_snake(Domains)
        domain_snake = to_snake(Domain)
        DOMAIN_SCREAM = to_scream_snake(Domain)
        pkg = Domain.lower()  # Java 패키지 세그먼트 — 소문자, 구분자 없음(관용)

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
        # naive_pluralize가 실제로 규칙(+s 이외)을 적용했는지 — 안내 메시지용
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
# 파일 템플릿 (경로는 basePackage 디렉터리 기준 상대 경로)
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
 * {@code find__Domains__} 조회 결과 — 목록과 총 개수를 함께 반환한다. 단건 조회도 이 타입을 재사용한다: {@code
 * __Domain__FindQuery.take}를 1로 설정해 호출한 뒤 {@code __domains__()}의 첫 번째 결과를 꺼내 쓴다(repository-pattern.md
 * 참고).
 */
public record __Domains__WithCount(List<__Domain__> __domains__, long count) {}
"""

TEMPLATES["domain/__Domain__Repository.java"] = """package __basepkg__.__pkg__.domain;

/**
 * __Domain__ Aggregate의 쓰기용 Repository 계약(domain 소유). 읽기 전용 조회는 별도의
 * application/query/__Domain__Query 인터페이스로 분리한다(cqrs-pattern.md 참고).
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
 * __Domain__ Aggregate Root — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다. 영속성 매핑은
 * infrastructure/persistence/__Domain__JpaEntity + __Domain__Mapper가 전담한다 (account/domain/Account.java와
 * 동일한 domain/JPA 분리 구조).
 */
public class __Domain__ {

    private String __domain__Id;
    private String ownerId;
    private __Domain__Status status;
    private LocalDateTime createdAt;

    private final List<Object> domainEvents = new ArrayList<>();

    private __Domain__() {}

    /**
     * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 __Domain__을 복원할 때 사용한다. create()와 달리 새
     * 식별자·상태를 만들지 않고 저장된 상태를 그대로 재구성한다.
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

    // 이벤트를 발행하지 않는 단순 상태 전이 예시 — 도메인 이벤트가 필요 없는 변경은 이렇게 그냥 상태만 바꾼다.
    public void activate() {
        if (this.status != __Domain__Status.PENDING) {
            throw new __Domain__Exception(
                    __Domain__Exception.ErrorCode.__ERR_ACTIVATION_REQUIRES_PENDING__,
                    "대기 상태의 __Domain__만 활성화할 수 있습니다.");
        }
        this.status = __Domain__Status.ACTIVE;
    }

    // 이벤트를 발행하는 상태 전이 예시.
    public void cancel(String reason) {
        if (this.status == __Domain__Status.CANCELLED) {
            throw new __Domain__Exception(
                    __Domain__Exception.ErrorCode.__ERR_ALREADY_CANCELLED__, "이미 취소된 __Domain__입니다.");
        }
        this.status = __Domain__Status.CANCELLED;
        this.domainEvents.add(new __Domain__CancelledEvent(this.__domain__Id, reason, LocalDateTime.now()));
    }

    /** Repository 구현체가 저장 직후 호출해 미발행 이벤트를 꺼내고 비운다(domain-events.md 참고). */
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
                                                "__Domain__을(를) 찾을 수 없습니다."));
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
                                                "__Domain__을(를) 찾을 수 없습니다."));
        __domain__.cancel(command.reason());
        __domain__Repository.save__Domain__(__domain__);
        // Outbox → SQS 발행/수신은 OutboxPoller/OutboxConsumer가 독립적으로 처리한다
        // (domain-events.md) — Command Service는 저장 후 곧바로 반환한다.
    }
}
"""

# ---- application/query ----

TEMPLATES["application/query/__Domain__Query.java"] = """package __basepkg__.__pkg__.application.query;

import __basepkg__.__pkg__.domain.__Domain__FindQuery;
import __basepkg__.__pkg__.domain.__Domains__WithCount;

/**
 * Query Service 전용 읽기 인터페이스. 쓰기용 {@code __Domain__Repository}(domain)와 분리된 좁은 계약이다. Query
 * Service는 이 인터페이스만 의존해야 한다 — 쓰기 메서드를 노출하지 않는다(cqrs-pattern.md 참고).
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
                                                "__Domain__을(를) 찾을 수 없습니다."));
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
 * Outbox에 쌓인 {@link __Domain__CancelledEvent}(내부 Domain Event)를 처리한다. {@code OutboxConsumer}가
 * 클래스패스의 모든 {@link OutboxEventHandler} 구현체를 자동으로 수집해, SQS에서 이 이벤트를 수신하면 이 핸들러를
 * 호출한다 — 별도의 수동 등록이 필요 없다(모듈 등록 파일이 없는 이유는 module-pattern.md 참고).
 *
 * <p>스캐폴딩 단계에서는 로깅만 한다 — 실제 후속 처리(알림, 다른 BC로의 Integration Event 발행 등)는 도메인 요구사항이
 * 정해지면 여기에 구현한다(account/application/event/AccountSuspendedEventHandler.java 참고).
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
                "__Domain__ 취소됨 - __domain__Id={}, reason={}", event.__domain__Id(), event.reason());
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
 * __pkg__/domain/__Domain__.java의 JPA 매핑 전용 대응물. Domain Aggregate(__Domain__)는 이 클래스를 전혀 알지 못한다 —
 * 변환은 __Domain__Mapper가 전담한다 (account/infrastructure/persistence/AccountJpaEntity와 동일한 구조,
 * layer-architecture.md 참고).
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

    /** 기존 row(id 보존)에 도메인 __Domain__의 최신 상태를 반영한다 — status 전이 저장에 사용. */
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
 * __Domain__(순수 도메인) ↔ __Domain__JpaEntity(JPA 매핑) 변환 전담 클래스. __Domain__RepositoryImpl 내부에서만
 * 사용된다 — Domain/Application 레이어는 이 클래스를 알지 못한다.
 */
final class __Domain__Mapper {

    private __Domain__Mapper() {}

    static __Domain__ toDomain(__Domain__JpaEntity entity) {
        return __Domain__.reconstitute(
                entity.get__Domain__Id(), entity.getOwnerId(), entity.getStatus(), entity.getCreatedAt());
    }

    /** 신규 __Domain__을 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
    static __Domain__JpaEntity toNewEntity(__Domain__ __domain__) {
        return new __Domain__JpaEntity(
                null,
                __domain__.get__Domain__Id(),
                __domain__.getOwnerId(),
                __domain__.getStatus(),
                __domain__.getCreatedAt());
    }

    /** 기존 엔티티(PK 보존)에 도메인 __Domain__의 최신 상태를 반영한다 — update 대상. */
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
 * __Domain__의 쓰기용 {@link __Domain__Repository}와 읽기용 {@link __Domain__Query}를 한 클래스에서 구현한다
 * (account/infrastructure/persistence/AccountRepositoryImpl과 동일한 구조). 각 Application 레이어는 자신에게 필요한
 * 좁은 인터페이스(Repository 또는 Query)만 주입받는다.
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
        // Aggregate 저장과 같은 물리 트랜잭션 안에서 Outbox에 이벤트를 기록한다(domain-events.md 참고).
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
        log.warn("__Domain__ 요청 실패", kv("code", e.code()), kv("message", e.getMessage()));
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
# 생성 로직
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
        # rel_path 자체도 토큰을 포함하므로(예: __Domain__.java) 렌더링해야 실제 파일명이 나온다.
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

    print(f"{names.Domain} 도메인 생성 완료: {java_root} ({len(written)}개 파일)")
    print(
        f"REST 경로: /{names.domains_kebab} "
        f"(POST 생성, POST /:{{{names.domain}Id}}/activate 활성화, "
        f"POST /:{{{names.domain}Id}}/cancel 취소, GET /:{{{names.domain}Id}} 조회)"
    )
    print(f"Flyway 마이그레이션: {migration_path}")
    print()
    if names.plural_is_irregular:
        print(
            f"참고: 나이브 복수형 규칙이 아닌 y->ies 규칙이 적용됐습니다 "
            f"({names.domain} -> {names.domains}). 불규칙 복수형(예: person -> people)이면 "
            f"find{names.Domains}/{names.domains}/{names.domains_kebab} 등을 수동으로 다듬어야 할 수 있습니다."
        )
    else:
        print(
            "참고: 나이브 복수형 규칙(+s / +es / y->ies)을 썼습니다 — 불규칙 복수형 도메인이면 "
            f"find{names.Domains}/{names.domains} 등을 수동으로 다듬어야 할 수 있습니다."
        )
    print()
    print(
        "모듈 등록 단계 없음: Spring이 @Service/@Component/@Repository/@RestController를 "
        "classpath 전체에서 자동 수집한다(component scanning) — OutboxEventHandler 구현체도 "
        "OutboxConsumer가 자동으로 주입받는다. 손으로 고칠 central wiring 파일이 없다."
    )
    print()
    print(f"다음: cd {project_root} && ./gradlew spotlessApply && ./gradlew build")
    print(f"      bash harness.sh {project_root}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="새 도메인 스캐폴딩 생성기 (Java Spring Boot)"
    )
    parser.add_argument("domain_name", help="PascalCase 도메인 이름 (예: Coupon, LoyaltyCategory)")
    parser.add_argument(
        "--out",
        dest="out",
        default=None,
        help="대상 프로젝트 루트(src/main/... 를 포함하는 디렉터리). 기본값: ../examples",
    )
    parser.add_argument(
        "--package",
        dest="package",
        default=DEFAULT_BASE_PACKAGE,
        help=f"베이스 패키지 (기본값: {DEFAULT_BASE_PACKAGE})",
    )
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    project_root = Path(args.out).resolve() if args.out else (script_dir / ".." / "examples").resolve()

    generate(args.domain_name, project_root, args.package)


if __name__ == "__main__":
    main()
