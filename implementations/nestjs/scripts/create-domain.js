#!/usr/bin/env node
// 새 도메인 스캐폴딩 생성기 — docs/reference.md의 "실전 구현 템플릿"(Order 예시)을
// 실제로 코드화해 harness(evaluators/) 전체를 통과시킨 뒤, 도메인 이름만 파라미터로
// 뽑아 재사용 가능하게 일반화한 것이다. Aggregate(단일 상태 필드) + CQRS
// CommandHandler/QueryHandler(CommandBus/QueryBus) + 도메인 이벤트 1종 + 전용
// OutboxRelay + Repository + Controller + DTO + Module까지 한 번에 생성한다.
//
// 사용법:
//   node scripts/create-domain.js <PascalCaseDomainName> [--out <targetSrcDir>] [--wire]
//
// 예:
//   node scripts/create-domain.js Coupon
//     → ../examples/src/coupon/ 아래에 생성(스크립트 기본 대상)
//   node scripts/create-domain.js Coupon --out /tmp/scratch-app/src --wire
//     → 지정한 src/ 아래 생성 + app-module.ts에 import/등록까지 자동 삽입
//
// --wire를 주지 않으면 app-module.ts는 건드리지 않고, 붙여넣을 import/등록 스니펫만
// 콘솔에 출력한다 — 기존 프로젝트의 app-module.ts를 스크립트가 임의로 고치는 걸
// 원치 않을 수 있어 기본값은 안전한 쪽(수동 적용)으로 둔다.

'use strict'

const fs = require('node:fs')
const path = require('node:path')

function toPascalCase(input) {
  return input.charAt(0).toUpperCase() + input.slice(1)
}

function toCamelCase(input) {
  return input.charAt(0).toLowerCase() + input.slice(1)
}

function toKebabCase(input) {
  return input.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
}

function toScreamingSnakeCase(input) {
  return input.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()
}

// 아주 단순한 규칙 기반 복수형 — 불규칙 복수형(예: Category → Categories)은
// 생성 후 수동으로 고쳐야 한다는 걸 스크립트 실행 결과에 안내한다.
function naivePluralize(input) {
  if (/[sxz]$|[cs]h$/.test(input)) return `${input}es`
  if (/[^aeiou]y$/.test(input)) return `${input.slice(0, -1)}ies`
  return `${input}s`
}

function buildNames(rawDomainName) {
  const Domain = toPascalCase(rawDomainName)
  const domain = toCamelCase(Domain)
  const domainKebab = toKebabCase(Domain)
  const domains = naivePluralize(domain)
  const Domains = toPascalCase(domains)
  // REST 경로용 복수형 kebab-case — domainKebab에서 하이픈만 지우고 's'를 붙이면
  // (예: loyalty-category → loyaltycategorys) 단어 경계가 사라지고 복수형 규칙도
  // 깨진다. 이미 올바르게 복수화한 Domains를 다시 kebab-case로 변환해야 한다
  // (loyalty-category → LoyaltyCategories → loyalty-categories).
  const domainsKebab = toKebabCase(Domains)
  const DOMAIN_SCREAM = toScreamingSnakeCase(Domain)
  return { Domain, domain, domainKebab, domains, Domains, domainsKebab, DOMAIN_SCREAM }
}

function writeFile(filePath, content) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, content, 'utf-8')
}

function generateFiles(n) {
  const files = {}

  // ---- Domain 레이어 ----
  files[`${n.domainKebab}/${n.domainKebab}-enum.ts`] = `export enum ${n.Domain}Status {
  PENDING = 'PENDING',
  ACTIVE = 'ACTIVE',
  CANCELLED = 'CANCELLED',
}
`

  files[`${n.domainKebab}/${n.domainKebab}-error-code.ts`] = `export enum ${n.Domain}ErrorCode {
  ${n.DOMAIN_SCREAM}_NOT_FOUND = '${n.DOMAIN_SCREAM}_NOT_FOUND',
  ${n.DOMAIN_SCREAM}_ALREADY_CANCELLED = '${n.DOMAIN_SCREAM}_ALREADY_CANCELLED',
}
`

  files[`${n.domainKebab}/${n.domainKebab}-error-message.ts`] = `export enum ${n.Domain}ErrorMessage {
  '${n.Domain}을(를) 찾을 수 없습니다.' = '${n.Domain}을(를) 찾을 수 없습니다.',
  '이미 취소된 ${n.Domain}입니다.' = '이미 취소된 ${n.Domain}입니다.',
}
`

  files[`${n.domainKebab}/domain/${n.domainKebab}-cancelled.ts`] = `export class ${n.Domain}Cancelled {
  public readonly ${n.domain}Id: string
  public readonly reason: string
  public readonly cancelledAt: Date

  constructor(params: { ${n.domain}Id: string; reason: string; cancelledAt: Date }) {
    this.${n.domain}Id = params.${n.domain}Id
    this.reason = params.reason
    this.cancelledAt = params.cancelledAt
  }
}
`

  files[`${n.domainKebab}/domain/${n.domainKebab}.ts`] = `import { generateId } from '@/common/generate-id'
import { ${n.Domain}Cancelled } from '@/${n.domainKebab}/domain/${n.domainKebab}-cancelled'
import { ${n.Domain}Status } from '@/${n.domainKebab}/${n.domainKebab}-enum'
import { ${n.Domain}ErrorMessage } from '@/${n.domainKebab}/${n.domainKebab}-error-message'

export type ${n.Domain}DomainEvent = ${n.Domain}Cancelled

export class ${n.Domain} {
  public readonly ${n.domain}Id: string
  public readonly ownerId: string
  public readonly createdAt: Date
  private _status: ${n.Domain}Status
  private readonly _events: ${n.Domain}DomainEvent[] = []

  constructor(params: {
    ${n.domain}Id?: string
    ownerId: string
    status: ${n.Domain}Status
    createdAt?: Date
  }) {
    this.${n.domain}Id = params.${n.domain}Id ?? generateId()
    this.ownerId = params.ownerId
    this._status = params.status
    this.createdAt = params.createdAt ?? new Date()
  }

  get status(): ${n.Domain}Status { return this._status }
  get domainEvents(): ${n.Domain}DomainEvent[] { return [...this._events] }

  public static create(params: { ownerId: string }): ${n.Domain} {
    return new ${n.Domain}({ ownerId: params.ownerId, status: ${n.Domain}Status.PENDING })
  }

  // 이벤트를 발행하지 않는 단순 상태 전이 예시 — 도메인 이벤트가 필요 없는 변경은
  // 이렇게 그냥 상태만 바꾼다.
  public activate(): void {
    this._status = ${n.Domain}Status.ACTIVE
  }

  // 이벤트를 발행하는 상태 전이 예시.
  public cancel(reason: string): void {
    if (this._status === ${n.Domain}Status.CANCELLED) {
      throw new Error(${n.Domain}ErrorMessage['이미 취소된 ${n.Domain}입니다.'])
    }
    this._status = ${n.Domain}Status.CANCELLED
    this._events.push(new ${n.Domain}Cancelled({ ${n.domain}Id: this.${n.domain}Id, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
`

  files[`${n.domainKebab}/domain/${n.domainKebab}-repository.ts`] = `import { ${n.Domain} } from '@/${n.domainKebab}/domain/${n.domainKebab}'
import { ${n.Domain}Status } from '@/${n.domainKebab}/${n.domainKebab}-enum'

export abstract class ${n.Domain}Repository {
  abstract find${n.Domains}(query: {
    readonly take: number
    readonly page: number
    readonly ${n.domain}Id?: string
    readonly ownerId?: string
    readonly status?: ${n.Domain}Status[]
  }): Promise<{ ${n.domains}: ${n.Domain}[]; count: number }>

  abstract save${n.Domain}(${n.domain}: ${n.Domain}): Promise<void>
}
`

  // ---- Application 레이어 — Command ----
  files[`${n.domainKebab}/application/command/create-${n.domainKebab}-command.ts`] = `export class Create${n.Domain}Command {
  public readonly ownerId: string

  constructor(command: Create${n.Domain}Command) {
    Object.assign(this, command)
  }
}
`

  files[`${n.domainKebab}/application/command/create-${n.domainKebab}-command-handler.ts`] = `import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxRelay } from '@/${n.domainKebab}/application/event/outbox-relay'
import { Create${n.Domain}Command } from '@/${n.domainKebab}/application/command/create-${n.domainKebab}-command'
import { ${n.Domain} } from '@/${n.domainKebab}/domain/${n.domainKebab}'
import { ${n.Domain}Repository } from '@/${n.domainKebab}/domain/${n.domainKebab}-repository'

@CommandHandler(Create${n.Domain}Command)
export class Create${n.Domain}CommandHandler implements ICommandHandler<Create${n.Domain}Command, ${n.Domain}> {
  constructor(
    private readonly ${n.domain}Repository: ${n.Domain}Repository,
    private readonly transactionManager: TransactionManager,
    private readonly outboxRelay: OutboxRelay
  ) {}

  public async execute(command: Create${n.Domain}Command): Promise<${n.Domain}> {
    const ${n.domain} = ${n.Domain}.create({ ownerId: command.ownerId })
    await this.transactionManager.run(async () => {
      await this.${n.domain}Repository.save${n.Domain}(${n.domain})
    })
    await this.outboxRelay.processPending()
    return ${n.domain}
  }
}
`

  files[`${n.domainKebab}/application/command/cancel-${n.domainKebab}-command.ts`] = `export class Cancel${n.Domain}Command {
  public readonly ${n.domain}Id: string
  public readonly reason: string

  constructor(command: Cancel${n.Domain}Command) {
    Object.assign(this, command)
  }
}
`

  files[`${n.domainKebab}/application/command/cancel-${n.domainKebab}-command-handler.ts`] = `import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxRelay } from '@/${n.domainKebab}/application/event/outbox-relay'
import { Cancel${n.Domain}Command } from '@/${n.domainKebab}/application/command/cancel-${n.domainKebab}-command'
import { ${n.Domain}Repository } from '@/${n.domainKebab}/domain/${n.domainKebab}-repository'
import { ${n.Domain}ErrorMessage as ErrorMessage } from '@/${n.domainKebab}/${n.domainKebab}-error-message'

@CommandHandler(Cancel${n.Domain}Command)
export class Cancel${n.Domain}CommandHandler implements ICommandHandler<Cancel${n.Domain}Command> {
  constructor(
    private readonly ${n.domain}Repository: ${n.Domain}Repository,
    private readonly transactionManager: TransactionManager,
    private readonly outboxRelay: OutboxRelay
  ) {}

  public async execute(command: Cancel${n.Domain}Command): Promise<void> {
    const ${n.domain} = await this.${n.domain}Repository
      .find${n.Domains}({ ${n.domain}Id: command.${n.domain}Id, take: 1, page: 0 })
      .then((r) => r.${n.domains}.pop())
    if (!${n.domain}) throw new Error(ErrorMessage['${n.Domain}을(를) 찾을 수 없습니다.'])

    ${n.domain}.cancel(command.reason)

    await this.transactionManager.run(async () => {
      await this.${n.domain}Repository.save${n.Domain}(${n.domain})
    })
    await this.outboxRelay.processPending()
  }
}
`

  // ---- Application 레이어 — Query ----
  files[`${n.domainKebab}/application/query/get-${n.domainKebab}-result.ts`] = `export interface Get${n.Domain}Result {
  ${n.domain}Id: string
  ownerId: string
  status: string
  createdAt: Date
}
`

  files[`${n.domainKebab}/application/query/${n.domainKebab}-query.ts`] = `import { Get${n.Domain}Result } from '@/${n.domainKebab}/application/query/get-${n.domainKebab}-result'

export abstract class ${n.Domain}Query {
  abstract get${n.Domain}(param: { ${n.domain}Id: string; ownerId: string }): Promise<Get${n.Domain}Result>
}
`

  files[`${n.domainKebab}/application/query/get-${n.domainKebab}-query.ts`] = `export class Get${n.Domain}Query {
  public readonly ${n.domain}Id: string
  public readonly requesterId: string

  constructor(query: Get${n.Domain}Query) {
    Object.assign(this, query)
  }
}
`

  files[`${n.domainKebab}/application/query/get-${n.domainKebab}-query-handler.ts`] = `import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { ${n.Domain}Query } from '@/${n.domainKebab}/application/query/${n.domainKebab}-query'
import { Get${n.Domain}Result } from '@/${n.domainKebab}/application/query/get-${n.domainKebab}-result'
import { Get${n.Domain}Query } from '@/${n.domainKebab}/application/query/get-${n.domainKebab}-query'

@QueryHandler(Get${n.Domain}Query)
export class Get${n.Domain}QueryHandler implements IQueryHandler<Get${n.Domain}Query, Get${n.Domain}Result> {
  constructor(private readonly ${n.domain}Query: ${n.Domain}Query) {}

  public async execute(query: Get${n.Domain}Query): Promise<Get${n.Domain}Result> {
    return this.${n.domain}Query.get${n.Domain}({ ${n.domain}Id: query.${n.domain}Id, ownerId: query.requesterId })
  }
}
`

  // ---- Application 레이어 — Event ----
  files[`${n.domainKebab}/application/event/${n.domainKebab}-cancelled-handler.ts`] = `import { Injectable, Logger } from '@nestjs/common'

// 취소된 ${n.Domain}에 대한 후속 처리(알림, 다른 BC로의 Integration Event 발행 등)를
// 여기서 구현한다. 스캐폴딩 단계에서는 로깅만 한다.
@Injectable()
export class ${n.Domain}CancelledHandler {
  private readonly logger = new Logger(${n.Domain}CancelledHandler.name)

  public async handle(event: { ${n.domain}Id: string; reason: string; cancelledAt: string }): Promise<void> {
    this.logger.log({ message: '${n.Domain} 취소됨', ${n.domain}_id: event.${n.domain}Id, reason: event.reason })
  }
}
`

  files[`${n.domainKebab}/application/event/outbox-relay.ts`] = `import { Injectable, Logger } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { OutboxEntity } from '@/outbox/outbox.entity'
import { ${n.Domain}CancelledHandler } from '@/${n.domainKebab}/application/event/${n.domainKebab}-cancelled-handler'

// 도메인마다 자기 이벤트만 처리하는 전용 OutboxRelay를 둔다 — 이게 이 저장소의
// 실제 컨벤션이다(harness의 domain-event-outbox.relay-handler-map-incomplete
// 규칙도 도메인별로 스코프를 검사한다, issue #229).
@Injectable()
export class OutboxRelay {
  private readonly logger = new Logger(OutboxRelay.name)
  private readonly handlers: Record<string, (payload: object) => Promise<void>>

  constructor(
    private readonly transactionManager: TransactionManager,
    private readonly registry: EventHandlerRegistry,
    ${n.domain}CancelledHandler: ${n.Domain}CancelledHandler
  ) {
    this.handlers = {
      ${n.Domain}Cancelled: (payload) => ${n.domain}CancelledHandler.handle(payload as never)
    }
  }

  public async processPending(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const MAX_PASSES = 10
    const failedInThisRun = new Set<string>()

    for (let pass = 0; pass < MAX_PASSES; pass++) {
      const rows = (await manager.findBy(OutboxEntity, { processed: false }))
        .filter((row) => !failedInThisRun.has(row.eventId))
      if (rows.length === 0) return

      let progressed = 0
      for (const row of rows) {
        try {
          const handler = this.handlers[row.eventType]
          if (handler) await handler(JSON.parse(row.payload))
          else await this.registry.handle(row.eventType, JSON.parse(row.payload))
          await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
          progressed++
        } catch (error) {
          failedInThisRun.add(row.eventId)
          this.logger.error({ message: '이벤트 처리 실패', event_type: row.eventType, event_id: row.eventId, error })
        }
      }
      if (progressed === 0) return
    }
  }
}
`

  // ---- Infrastructure 레이어 ----
  files[`${n.domainKebab}/infrastructure/entity/${n.domainKebab}.entity.ts`] = `import { Column, Entity, PrimaryColumn } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'

@Entity('${n.domainKebab.replace(/-/g, '_')}')
export class ${n.Domain}Entity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  ${n.domain}Id: string

  @Column()
  ownerId: string

  @Column()
  status: string
}
`

  files[`${n.domainKebab}/infrastructure/${n.domainKebab}-repository-impl.ts`] = `import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { ${n.Domain} } from '@/${n.domainKebab}/domain/${n.domainKebab}'
import { ${n.Domain}Repository } from '@/${n.domainKebab}/domain/${n.domainKebab}-repository'
import { ${n.Domain}Status } from '@/${n.domainKebab}/${n.domainKebab}-enum'
import { ${n.Domain}Entity } from '@/${n.domainKebab}/infrastructure/entity/${n.domainKebab}.entity'

@Injectable()
export class ${n.Domain}RepositoryImpl extends ${n.Domain}Repository {
  constructor(
    @InjectRepository(${n.Domain}Entity) private readonly ${n.domain}Repo: Repository<${n.Domain}Entity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async find${n.Domains}(query: {
    readonly take: number
    readonly page: number
    readonly ${n.domain}Id?: string
    readonly ownerId?: string
    readonly status?: ${n.Domain}Status[]
  }): Promise<{ ${n.domains}: ${n.Domain}[]; count: number }> {
    const qb = this.${n.domain}Repo.createQueryBuilder('${n.domain}')
      .orderBy('${n.domain}.${n.domain}Id', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.${n.domain}Id) qb.andWhere('${n.domain}.${n.domain}Id = :${n.domain}Id', { ${n.domain}Id: query.${n.domain}Id })
    if (query.ownerId) qb.andWhere('${n.domain}.ownerId = :ownerId', { ownerId: query.ownerId })
    if (query.status?.length) qb.andWhere('${n.domain}.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      ${n.domains}: rows.map((row) => new ${n.Domain}({
        ${n.domain}Id: row.${n.domain}Id,
        ownerId: row.ownerId,
        status: row.status as ${n.Domain}Status,
        createdAt: row.createdAt
      })),
      count
    }
  }

  public async save${n.Domain}(${n.domain}: ${n.Domain}): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(${n.Domain}Entity, {
      ${n.domain}Id: ${n.domain}.${n.domain}Id,
      ownerId: ${n.domain}.ownerId,
      status: ${n.domain}.status
    })
    if (${n.domain}.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(${n.domain}.domainEvents)
      ${n.domain}.clearEvents()
    }
  }
}
`

  files[`${n.domainKebab}/infrastructure/${n.domainKebab}-query-impl.ts`] = `import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { ${n.Domain}Query } from '@/${n.domainKebab}/application/query/${n.domainKebab}-query'
import { Get${n.Domain}Result } from '@/${n.domainKebab}/application/query/get-${n.domainKebab}-result'
import { ${n.Domain}Entity } from '@/${n.domainKebab}/infrastructure/entity/${n.domainKebab}.entity'
import { ${n.Domain}ErrorMessage as ErrorMessage } from '@/${n.domainKebab}/${n.domainKebab}-error-message'

@Injectable()
export class ${n.Domain}QueryImpl extends ${n.Domain}Query {
  constructor(
    @InjectRepository(${n.Domain}Entity) private readonly ${n.domain}Repo: Repository<${n.Domain}Entity>
  ) {
    super()
  }

  public async get${n.Domain}(param: { ${n.domain}Id: string; ownerId: string }): Promise<Get${n.Domain}Result> {
    const row = await this.${n.domain}Repo.createQueryBuilder('${n.domain}')
      .where('${n.domain}.${n.domain}Id = :${n.domain}Id', { ${n.domain}Id: param.${n.domain}Id })
      .andWhere('${n.domain}.ownerId = :ownerId', { ownerId: param.ownerId })
      .getOne()
    if (!row) throw new Error(ErrorMessage['${n.Domain}을(를) 찾을 수 없습니다.'])

    return {
      ${n.domain}Id: row.${n.domain}Id,
      ownerId: row.ownerId,
      status: row.status,
      createdAt: row.createdAt
    }
  }
}
`

  // ---- Interface 레이어 ----
  files[`${n.domainKebab}/interface/dto/create-${n.domainKebab}-response-body.ts`] = `import { ApiProperty } from '@nestjs/swagger'

export class Create${n.Domain}ResponseBody {
  @ApiProperty()
  public readonly ${n.domain}Id: string

  @ApiProperty()
  public readonly ownerId: string

  @ApiProperty()
  public readonly status: string

  @ApiProperty()
  public readonly createdAt: Date
}
`

  files[`${n.domainKebab}/interface/dto/cancel-${n.domainKebab}-request-body.ts`] = `import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class Cancel${n.Domain}RequestBody {
  @ApiProperty({ minLength: 1 })
  @IsString()
  @MinLength(1)
  public readonly reason: string
}
`

  files[`${n.domainKebab}/interface/dto/get-${n.domainKebab}-request-param.ts`] = `import { ApiProperty } from '@nestjs/swagger'
import { IsString } from 'class-validator'

export class Get${n.Domain}RequestParam {
  @ApiProperty()
  @IsString()
  public readonly ${n.domain}Id: string
}
`

  files[`${n.domainKebab}/interface/dto/get-${n.domainKebab}-response-body.ts`] = `import { ApiProperty } from '@nestjs/swagger'

export class Get${n.Domain}ResponseBody {
  @ApiProperty()
  public readonly ${n.domain}Id: string

  @ApiProperty()
  public readonly ownerId: string

  @ApiProperty()
  public readonly status: string

  @ApiProperty()
  public readonly createdAt: Date
}
`

  files[`${n.domainKebab}/interface/${n.domainKebab}-controller.ts`] = `import {
  Body, Controller, Get, HttpCode, Logger,
  NotFoundException, Param, Post, Req, UseGuards
} from '@nestjs/common'
import { ApiBearerAuth, ApiCreatedResponse, ApiNoContentResponse, ApiOkResponse, ApiOperation, ApiTags } from '@nestjs/swagger'
import { CommandBus, QueryBus } from '@nestjs/cqrs'
import { Request } from 'express'

import { generateErrorResponse } from '@/common/generate-error-response'
import { AuthGuard } from '@/auth/auth.guard'
import { Cancel${n.Domain}Command } from '@/${n.domainKebab}/application/command/cancel-${n.domainKebab}-command'
import { Create${n.Domain}Command } from '@/${n.domainKebab}/application/command/create-${n.domainKebab}-command'
import { ${n.Domain} } from '@/${n.domainKebab}/domain/${n.domainKebab}'
import { Get${n.Domain}Query } from '@/${n.domainKebab}/application/query/get-${n.domainKebab}-query'
import { Get${n.Domain}Result } from '@/${n.domainKebab}/application/query/get-${n.domainKebab}-result'
import { Cancel${n.Domain}RequestBody } from '@/${n.domainKebab}/interface/dto/cancel-${n.domainKebab}-request-body'
import { Create${n.Domain}ResponseBody } from '@/${n.domainKebab}/interface/dto/create-${n.domainKebab}-response-body'
import { Get${n.Domain}RequestParam } from '@/${n.domainKebab}/interface/dto/get-${n.domainKebab}-request-param'
import { Get${n.Domain}ResponseBody } from '@/${n.domainKebab}/interface/dto/get-${n.domainKebab}-response-body'
import { ${n.Domain}ErrorCode as ErrorCode } from '@/${n.domainKebab}/${n.domainKebab}-error-code'
import { ${n.Domain}ErrorMessage } from '@/${n.domainKebab}/${n.domainKebab}-error-message'

type AuthenticatedRequest = Request & { user: { userId: string } }

@Controller()
@ApiTags('${n.Domain}')
@ApiBearerAuth('token')
@UseGuards(AuthGuard)
export class ${n.Domain}Controller {
  private readonly logger = new Logger(${n.Domain}Controller.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Post('/${n.domainsKebab}')
  @ApiOperation({ operationId: 'create${n.Domain}' })
  @ApiCreatedResponse({ type: Create${n.Domain}ResponseBody })
  public async create${n.Domain}(
    @Req() req: AuthenticatedRequest
  ): Promise<Create${n.Domain}ResponseBody> {
    const ownerId = req.user.userId
    return this.commandBus.execute<Create${n.Domain}Command, ${n.Domain}>(new Create${n.Domain}Command({ ownerId }))
      .then((${n.domain}) => ({
        ${n.domain}Id: ${n.domain}.${n.domain}Id,
        ownerId: ${n.domain}.ownerId,
        status: ${n.domain}.status,
        createdAt: ${n.domain}.createdAt
      }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [])
      })
  }

  @Get('/${n.domainsKebab}/:${n.domain}Id')
  @ApiOperation({ operationId: 'get${n.Domain}' })
  @ApiOkResponse({ type: Get${n.Domain}ResponseBody })
  public async get${n.Domain}(
    @Req() req: AuthenticatedRequest,
    @Param() param: Get${n.Domain}RequestParam
  ): Promise<Get${n.Domain}ResponseBody> {
    const requesterId = req.user.userId
    return this.queryBus.execute<Get${n.Domain}Query, Get${n.Domain}Result>(
      new Get${n.Domain}Query({ ${n.domain}Id: param.${n.domain}Id, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [${n.Domain}ErrorMessage['${n.Domain}을(를) 찾을 수 없습니다.'], NotFoundException, ErrorCode.${n.DOMAIN_SCREAM}_NOT_FOUND]
      ])
    })
  }

  @Post('/${n.domainsKebab}/:${n.domain}Id/cancel')
  @HttpCode(204)
  @ApiOperation({ operationId: 'cancel${n.Domain}' })
  @ApiNoContentResponse()
  public async cancel${n.Domain}(
    @Param('${n.domain}Id') ${n.domain}Id: string,
    @Body() body: Cancel${n.Domain}RequestBody
  ): Promise<void> {
    return this.commandBus.execute<Cancel${n.Domain}Command, void>(new Cancel${n.Domain}Command({ ...body, ${n.domain}Id })).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [${n.Domain}ErrorMessage['${n.Domain}을(를) 찾을 수 없습니다.'], NotFoundException, ErrorCode.${n.DOMAIN_SCREAM}_NOT_FOUND]
      ])
    })
  }
}
`

  // ---- Module ----
  files[`${n.domainKebab}/${n.domainKebab}-module.ts`] = `import { Module } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AuthModule } from '@/auth/auth-module'
import { Cancel${n.Domain}CommandHandler } from '@/${n.domainKebab}/application/command/cancel-${n.domainKebab}-command-handler'
import { Create${n.Domain}CommandHandler } from '@/${n.domainKebab}/application/command/create-${n.domainKebab}-command-handler'
import { ${n.Domain}CancelledHandler } from '@/${n.domainKebab}/application/event/${n.domainKebab}-cancelled-handler'
import { OutboxRelay } from '@/${n.domainKebab}/application/event/outbox-relay'
import { Get${n.Domain}QueryHandler } from '@/${n.domainKebab}/application/query/get-${n.domainKebab}-query-handler'
import { ${n.Domain}Query } from '@/${n.domainKebab}/application/query/${n.domainKebab}-query'
import { ${n.Domain}Repository } from '@/${n.domainKebab}/domain/${n.domainKebab}-repository'
import { ${n.Domain}Entity } from '@/${n.domainKebab}/infrastructure/entity/${n.domainKebab}.entity'
import { ${n.Domain}QueryImpl } from '@/${n.domainKebab}/infrastructure/${n.domainKebab}-query-impl'
import { ${n.Domain}RepositoryImpl } from '@/${n.domainKebab}/infrastructure/${n.domainKebab}-repository-impl'
import { ${n.Domain}Controller } from '@/${n.domainKebab}/interface/${n.domainKebab}-controller'

@Module({
  imports: [CqrsModule, TypeOrmModule.forFeature([${n.Domain}Entity]), AuthModule],
  controllers: [${n.Domain}Controller],
  providers: [
    // Command Handlers
    Create${n.Domain}CommandHandler,
    Cancel${n.Domain}CommandHandler,
    // Query Handlers
    Get${n.Domain}QueryHandler,
    // Domain Event 후속 처리 + Outbox 드레인
    ${n.Domain}CancelledHandler,
    OutboxRelay,
    // Repositories
    { provide: ${n.Domain}Repository, useClass: ${n.Domain}RepositoryImpl },
    // Query 구현체
    { provide: ${n.Domain}Query, useClass: ${n.Domain}QueryImpl }
  ]
})
export class ${n.Domain}Module {}
`

  return files
}

function printAppModuleSnippet(n) {
  console.log('')
  console.log('--- app-module.ts에 수동으로 추가할 내용 (--wire를 주지 않았으므로 자동 적용 안 됨) ---')
  console.log('')
  console.log(`import { ${n.Domain}Module } from '@/${n.domainKebab}/${n.domainKebab}-module'`)
  console.log('')
  console.log(`  // imports 배열에 추가:\n    ${n.Domain}Module`)
  console.log('')
}

function wireAppModule(appModulePath, n) {
  if (!fs.existsSync(appModulePath)) {
    console.warn(`app-module.ts를 찾지 못해 자동 wiring을 건너뜁니다: ${appModulePath}`)
    printAppModuleSnippet(n)
    return
  }
  let content = fs.readFileSync(appModulePath, 'utf-8')

  const importLine = `import { ${n.Domain}Module } from '@/${n.domainKebab}/${n.domainKebab}-module'`
  const alreadyImported = content.includes(importLine)
  const importsArrayMatch = /imports:\s*\[/.exec(content)
  const alreadyRegistered = !!importsArrayMatch
    && new RegExp(`imports:\\s*\\[[^]*?\\b${n.Domain}Module\\b`).test(content)

  if (alreadyImported && alreadyRegistered) {
    console.log(`이미 app-module.ts에 ${n.Domain}Module이 등록돼 있어 건너뜁니다.`)
    return
  }

  if (!alreadyImported) {
    // 마지막 import 문 다음 줄에 삽입
    const importRegex = /^import .+$/gm
    let lastImportEnd = 0
    let m
    while ((m = importRegex.exec(content)) !== null) lastImportEnd = m.index + m[0].length
    content = `${content.slice(0, lastImportEnd)}\n${importLine}${content.slice(lastImportEnd)}`
  }

  if (!importsArrayMatch) {
    console.warn('imports: [ 배열을 찾지 못해 자동 등록을 건너뜁니다. 아래를 수동으로 추가하세요.')
    console.log(`    ${n.Domain}Module`)
  } else if (!alreadyRegistered) {
    // 배열 맨 앞에 삽입한다 — 닫는 대괄호를 정규식으로 찾으면 imports 배열 안에 중첩된
    // 다른 배열(예: ConfigModule.forRoot({ load: [...] }))의 첫 `]`에 걸려 잘못된
    // 위치에 삽입되는 버그가 있어(중첩 괄호는 정규식으로 안전하게 못 다룸), 여는
    // 대괄호 바로 뒤에 붙이는 쪽이 훨씬 안전하다.
    content = content.replace(/imports:\s*\[/, `imports: [\n    ${n.Domain}Module,`)
  }

  fs.writeFileSync(appModulePath, content, 'utf-8')
  console.log(`app-module.ts에 ${n.Domain}Module import/등록 완료: ${appModulePath}`)
}

function main() {
  const args = process.argv.slice(2)
  const rawDomainName = args[0]
  if (!rawDomainName || rawDomainName.startsWith('--')) {
    console.error('사용법: node scripts/create-domain.js <PascalCaseDomainName> [--out <targetSrcDir>] [--wire]')
    process.exit(1)
  }

  const outIdx = args.indexOf('--out')
  const targetSrcDir = outIdx >= 0 ? args[outIdx + 1] : path.join(__dirname, '..', 'examples', 'src')
  const shouldWire = args.includes('--wire')

  const n = buildNames(rawDomainName)
  const files = generateFiles(n)

  for (const [relPath, content] of Object.entries(files)) {
    writeFile(path.join(targetSrcDir, relPath), content)
  }

  console.log(`${n.Domain} 도메인 생성 완료: ${path.join(targetSrcDir, n.domainKebab)}/ (${Object.keys(files).length}개 파일)`)
  console.log(`REST 경로: /${n.domainsKebab} (POST 생성, GET/:${n.domain}Id 조회, POST /:${n.domain}Id/cancel 취소)`)
  console.log('')
  console.log('참고: 나이브 복수형 규칙(+s / +es / y→ies)을 썼습니다 — 불규칙 복수형 도메인이면')
  console.log(`  find${n.Domains}/${n.domains} 등을 수동으로 다듬어야 할 수 있습니다.`)

  if (shouldWire) {
    wireAppModule(path.join(targetSrcDir, 'app-module.ts'), n)
  } else {
    printAppModuleSnippet(n)
  }

  console.log('다음: bash harness.sh <projectRoot>로 검증하세요.')
}

main()
