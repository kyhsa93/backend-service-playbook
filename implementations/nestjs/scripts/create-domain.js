#!/usr/bin/env node
// A new-domain scaffolding generator — turns the "Reference Implementation Template" (the
// Order example) in docs/reference.md into real code, passes the entire harness
// (evaluators/), then generalizes it by parameterizing only the domain name so it can be
// reused. It generates, in one pass, the Aggregate (a single state field) + CQRS
// CommandHandler/QueryHandler (CommandBus/QueryBus) + one domain event + an OnModuleInit that
// registers a handler with the shared outbox module (EventHandlerRegistry) + Repository +
// Controller + DTO + Module. Outbox draining is handled not by a per-domain Relay but by the
// shared OutboxPoller/OutboxConsumer (see domain-events.md).
//
// Usage:
//   node scripts/create-domain.js <PascalCaseDomainName> [--out <targetSrcDir>] [--wire]
//
// Examples:
//   node scripts/create-domain.js Coupon
//     → generates under ../examples/src/coupon/ (the script's default target)
//   node scripts/create-domain.js Coupon --out /tmp/scratch-app/src --wire
//     → generates under the specified src/ + auto-inserts the import/registration into app-module.ts
//
// If --wire isn't given, app-module.ts is left untouched, and only the import/registration
// snippet to paste in is printed to the console — since you might not want the script to
// arbitrarily modify an existing project's app-module.ts, the default is the safe side (manual application).

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

// A very simple rule-based pluralizer — the script's run output notes that an irregular
// plural (e.g. Category → Categories) needs to be fixed manually after generation.
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
  // The plural kebab-case for the REST path — if you just strip the hyphens from domainKebab
  // and append 's' (e.g. loyalty-category → loyaltycategorys), the word boundary disappears and
  // the pluralization rule breaks too. Domains, which was already correctly pluralized, must be
  // converted back to kebab-case instead (loyalty-category → LoyaltyCategories → loyalty-categories).
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

  // ---- Domain layer ----
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
  '${n.Domain} not found.' = '${n.Domain} not found.',
  'The ${n.domain} is already cancelled.' = 'The ${n.domain} is already cancelled.',
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

  // A simple state-transition example that doesn't publish an event — a change that doesn't
  // need a domain event just changes the state like this.
  public activate(): void {
    this._status = ${n.Domain}Status.ACTIVE
  }

  // A state-transition example that publishes an event.
  public cancel(reason: string): void {
    if (this._status === ${n.Domain}Status.CANCELLED) {
      throw new Error(${n.Domain}ErrorMessage['The ${n.domain} is already cancelled.'])
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

  // ---- Application layer — Command ----
  files[`${n.domainKebab}/application/command/create-${n.domainKebab}-command.ts`] = `export class Create${n.Domain}Command {
  public readonly ownerId: string

  constructor(command: Create${n.Domain}Command) {
    Object.assign(this, command)
  }
}
`

  files[`${n.domainKebab}/application/command/create-${n.domainKebab}-command-handler.ts`] = `import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { Create${n.Domain}Command } from '@/${n.domainKebab}/application/command/create-${n.domainKebab}-command'
import { ${n.Domain} } from '@/${n.domainKebab}/domain/${n.domainKebab}'
import { ${n.Domain}Repository } from '@/${n.domainKebab}/domain/${n.domainKebab}-repository'

@CommandHandler(Create${n.Domain}Command)
export class Create${n.Domain}CommandHandler implements ICommandHandler<Create${n.Domain}Command, ${n.Domain}> {
  constructor(
    private readonly ${n.domain}Repository: ${n.Domain}Repository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: Create${n.Domain}Command): Promise<${n.Domain}> {
    const ${n.domain} = ${n.Domain}.create({ ownerId: command.ownerId })
    await this.transactionManager.run(async () => {
      await this.${n.domain}Repository.save${n.Domain}(${n.domain})
    })
    // Draining the Outbox is handled independently by the shared OutboxPoller/OutboxConsumer
    // running on their own schedule — the Command Handler just returns once the save is done.
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
import { Cancel${n.Domain}Command } from '@/${n.domainKebab}/application/command/cancel-${n.domainKebab}-command'
import { ${n.Domain}Repository } from '@/${n.domainKebab}/domain/${n.domainKebab}-repository'
import { ${n.Domain}ErrorMessage as ErrorMessage } from '@/${n.domainKebab}/${n.domainKebab}-error-message'

@CommandHandler(Cancel${n.Domain}Command)
export class Cancel${n.Domain}CommandHandler implements ICommandHandler<Cancel${n.Domain}Command> {
  constructor(
    private readonly ${n.domain}Repository: ${n.Domain}Repository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: Cancel${n.Domain}Command): Promise<void> {
    const ${n.domain} = await this.${n.domain}Repository
      .find${n.Domains}({ ${n.domain}Id: command.${n.domain}Id, take: 1, page: 0 })
      .then((r) => r.${n.domains}.pop())
    if (!${n.domain}) throw new Error(ErrorMessage['${n.Domain} not found.'])

    ${n.domain}.cancel(command.reason)

    await this.transactionManager.run(async () => {
      await this.${n.domain}Repository.save${n.Domain}(${n.domain})
    })
    // Draining the Outbox is handled independently by the shared OutboxPoller/OutboxConsumer
    // running on their own schedule — the Command Handler just returns once the save is done.
  }
}
`

  // ---- Application layer — Query ----
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

  // ---- Application layer — Event ----
  files[`${n.domainKebab}/application/event/${n.domainKebab}-cancelled-handler.ts`] = `import { Injectable, Logger } from '@nestjs/common'

// Implement follow-up processing for a cancelled ${n.Domain} here (a notification, publishing
// an Integration Event to another BC, etc.). At the scaffolding stage, only logging is done.
@Injectable()
export class ${n.Domain}CancelledHandler {
  private readonly logger = new Logger(${n.Domain}CancelledHandler.name)

  public async handle(event: { ${n.domain}Id: string; reason: string; cancelledAt: string }): Promise<void> {
    this.logger.log({ message: '${n.Domain} cancelled', ${n.domain}_id: event.${n.domain}Id, reason: event.reason })
  }
}
`

  // ---- Infrastructure layer ----
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
    if (!row) throw new Error(ErrorMessage['${n.Domain} not found.'])

    return {
      ${n.domain}Id: row.${n.domain}Id,
      ownerId: row.ownerId,
      status: row.status,
      createdAt: row.createdAt
    }
  }
}
`

  // ---- Interface layer ----
  files[`${n.domainKebab}/interface/dto/create-${n.domainKebab}-response-body.ts`] = `import { ApiProperty } from '@nestjs/swagger'

export class Create${n.Domain}ResponseBody {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the ${n.domain}.' })
  public readonly ${n.domain}Id: string

  @ApiProperty({ description: 'The ID of the user who owns this ${n.domain}.' })
  public readonly ownerId: string

  @ApiProperty({ description: 'The ${n.domain} status.', enum: ['PENDING', 'ACTIVE', 'CANCELLED'] })
  public readonly status: string

  @ApiProperty({ description: 'When the ${n.domain} was created.' })
  public readonly createdAt: Date
}
`

  files[`${n.domainKebab}/interface/dto/cancel-${n.domainKebab}-request-body.ts`] = `import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class Cancel${n.Domain}RequestBody {
  @ApiProperty({ description: 'Why the ${n.domain} is being cancelled.', minLength: 1 })
  @IsString()
  @MinLength(1)
  public readonly reason: string
}
`

  files[`${n.domainKebab}/interface/dto/get-${n.domainKebab}-request-param.ts`] = `import { ApiProperty } from '@nestjs/swagger'
import { IsString } from 'class-validator'

export class Get${n.Domain}RequestParam {
  @ApiProperty({ description: 'The ${n.domain} ID.' })
  @IsString()
  public readonly ${n.domain}Id: string
}
`

  files[`${n.domainKebab}/interface/dto/get-${n.domainKebab}-response-body.ts`] = `import { ApiProperty } from '@nestjs/swagger'

export class Get${n.Domain}ResponseBody {
  @ApiProperty({ description: 'A 32-character hex string uniquely identifying the ${n.domain}.' })
  public readonly ${n.domain}Id: string

  @ApiProperty({ description: 'The ID of the user who owns this ${n.domain}.' })
  public readonly ownerId: string

  @ApiProperty({ description: 'The ${n.domain} status.', enum: ['PENDING', 'ACTIVE', 'CANCELLED'] })
  public readonly status: string

  @ApiProperty({ description: 'When the ${n.domain} was created.' })
  public readonly createdAt: Date
}
`

  files[`${n.domainKebab}/interface/${n.domainKebab}-controller.ts`] = `import {
  BadRequestException, Body, Controller, Get, HttpCode, Logger,
  NotFoundException, Param, Post
} from '@nestjs/common'
import {
  ApiBadRequestResponse, ApiBearerAuth, ApiCreatedResponse, ApiNoContentResponse,
  ApiNotFoundResponse, ApiOkResponse, ApiOperation, ApiTags, ApiUnauthorizedResponse
} from '@nestjs/swagger'
import { CommandBus, QueryBus } from '@nestjs/cqrs'

import { Authenticated } from '@/auth/authenticated.decorator'
import { generateErrorResponse } from '@/common/generate-error-response'
import { ErrorResponseBody } from '@/common/interface/dto/error-response-body'
import { UserContextStore } from '@/common/user-context-store'
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

@Controller()
@ApiTags('${n.Domain}')
@ApiBearerAuth('token')
@ApiUnauthorizedResponse({ description: 'The bearer token is missing, malformed, or invalid.', type: ErrorResponseBody })
@Authenticated()
export class ${n.Domain}Controller {
  private readonly logger = new Logger(${n.Domain}Controller.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Post('/${n.domainsKebab}')
  @ApiOperation({
    operationId: 'create${n.Domain}',
    summary: 'Create a ${n.domain}',
    description: 'Creates a new ${n.domain} owned by the authenticated requester, starting in PENDING status.'
  })
  @ApiCreatedResponse({ description: 'The ${n.domain} was created.', type: Create${n.Domain}ResponseBody })
  public async create${n.Domain}(): Promise<Create${n.Domain}ResponseBody> {
    const ownerId = UserContextStore.getRequesterId()
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
  @ApiOperation({
    operationId: 'get${n.Domain}',
    summary: 'Look up a ${n.domain}',
    description: 'Returns the ${n.domain} only if it belongs to the authenticated requester.'
  })
  @ApiOkResponse({ description: 'The ${n.domain} was found.', type: Get${n.Domain}ResponseBody })
  @ApiNotFoundResponse({ description: 'No ${n.domain} exists with the given \`${n.domain}Id\` for this requester (\`${n.DOMAIN_SCREAM}_NOT_FOUND\`).', type: ErrorResponseBody })
  public async get${n.Domain}(
    @Param() param: Get${n.Domain}RequestParam
  ): Promise<Get${n.Domain}ResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.queryBus.execute<Get${n.Domain}Query, Get${n.Domain}Result>(
      new Get${n.Domain}Query({ ${n.domain}Id: param.${n.domain}Id, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [${n.Domain}ErrorMessage['${n.Domain} not found.'], NotFoundException, ErrorCode.${n.DOMAIN_SCREAM}_NOT_FOUND]
      ])
    })
  }

  @Post('/${n.domainsKebab}/:${n.domain}Id/cancel')
  @HttpCode(204)
  @ApiOperation({
    operationId: 'cancel${n.Domain}',
    summary: 'Cancel a ${n.domain}',
    description: 'Cancels a ${n.domain} that has not already been cancelled.'
  })
  @ApiNoContentResponse({ description: 'The ${n.domain} was cancelled.' })
  @ApiBadRequestResponse({ description: 'The ${n.domain} is already cancelled (\`${n.DOMAIN_SCREAM}_ALREADY_CANCELLED\`).', type: ErrorResponseBody })
  @ApiNotFoundResponse({ description: 'No ${n.domain} exists with the given \`${n.domain}Id\` (\`${n.DOMAIN_SCREAM}_NOT_FOUND\`).', type: ErrorResponseBody })
  public async cancel${n.Domain}(
    @Param('${n.domain}Id') ${n.domain}Id: string,
    @Body() body: Cancel${n.Domain}RequestBody
  ): Promise<void> {
    return this.commandBus.execute<Cancel${n.Domain}Command, void>(new Cancel${n.Domain}Command({ ...body, ${n.domain}Id })).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [${n.Domain}ErrorMessage['${n.Domain} not found.'], NotFoundException, ErrorCode.${n.DOMAIN_SCREAM}_NOT_FOUND],
        [${n.Domain}ErrorMessage['The ${n.domain} is already cancelled.'], BadRequestException, ErrorCode.${n.DOMAIN_SCREAM}_ALREADY_CANCELLED]
      ])
    })
  }
}
`

  // ---- Module ----
  files[`${n.domainKebab}/${n.domainKebab}-module.ts`] = `import { Module, OnModuleInit } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { EventHandlerRegistry } from '@/outbox/event-handler-registry'
import { AuthModule } from '@/auth/auth-module'
import { Cancel${n.Domain}CommandHandler } from '@/${n.domainKebab}/application/command/cancel-${n.domainKebab}-command-handler'
import { Create${n.Domain}CommandHandler } from '@/${n.domainKebab}/application/command/create-${n.domainKebab}-command-handler'
import { ${n.Domain}CancelledHandler } from '@/${n.domainKebab}/application/event/${n.domainKebab}-cancelled-handler'
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
    // Domain Event follow-up processing — the actual Outbox draining is handled by the shared
    // outbox module's OutboxPoller/OutboxConsumer, and this handler is registered with the
    // EventHandlerRegistry in onModuleInit below.
    ${n.Domain}CancelledHandler,
    // Repositories
    { provide: ${n.Domain}Repository, useClass: ${n.Domain}RepositoryImpl },
    // The Query implementation
    { provide: ${n.Domain}Query, useClass: ${n.Domain}QueryImpl }
  ]
})
export class ${n.Domain}Module implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly ${n.domain}CancelledHandler: ${n.Domain}CancelledHandler
  ) {}

  // Registers this domain's own published Domain Event (the handler to call when
  // OutboxConsumer receives it from SQS) into the shared EventHandlerRegistry — no per-domain
  // OutboxRelay is kept (the same pattern as account-module.ts).
  onModuleInit(): void {
    this.registry.register('${n.Domain}Cancelled', (payload) => this.${n.domain}CancelledHandler.handle(payload as never))
  }
}
`

  return files
}

function printAppModuleSnippet(n) {
  console.log('')
  console.log('--- Content to add manually to app-module.ts (not applied automatically since --wire was not given) ---')
  console.log('')
  console.log(`import { ${n.Domain}Module } from '@/${n.domainKebab}/${n.domainKebab}-module'`)
  console.log('')
  console.log(`  // add to the imports array:\n    ${n.Domain}Module`)
  console.log('')
}

function wireAppModule(appModulePath, n) {
  if (!fs.existsSync(appModulePath)) {
    console.warn(`Could not find app-module.ts, skipping automatic wiring: ${appModulePath}`)
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
    console.log(`${n.Domain}Module is already registered in app-module.ts, skipping.`)
    return
  }

  if (!alreadyImported) {
    // Insert on the line after the last import statement
    const importRegex = /^import .+$/gm
    let lastImportEnd = 0
    let m
    while ((m = importRegex.exec(content)) !== null) lastImportEnd = m.index + m[0].length
    content = `${content.slice(0, lastImportEnd)}\n${importLine}${content.slice(lastImportEnd)}`
  }

  if (!importsArrayMatch) {
    console.warn('Could not find the imports: [ array, skipping automatic registration. Add the following manually.')
    console.log(`    ${n.Domain}Module`)
  } else if (!alreadyRegistered) {
    // Inserts at the very front of the array — searching for the closing bracket via regex has
    // a bug where it can latch onto the first `]` of another array nested inside the imports
    // array (e.g. ConfigModule.forRoot({ load: [...] })) and insert in the wrong place (nested
    // brackets can't be safely handled with regex), so appending right after the opening bracket is much safer.
    content = content.replace(/imports:\s*\[/, `imports: [\n    ${n.Domain}Module,`)
  }

  fs.writeFileSync(appModulePath, content, 'utf-8')
  console.log(`${n.Domain}Module import/registration complete in app-module.ts: ${appModulePath}`)
}

function main() {
  const args = process.argv.slice(2)
  const rawDomainName = args[0]
  if (!rawDomainName || rawDomainName.startsWith('--')) {
    console.error('Usage: node scripts/create-domain.js <PascalCaseDomainName> [--out <targetSrcDir>] [--wire]')
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

  console.log(`${n.Domain} domain generation complete: ${path.join(targetSrcDir, n.domainKebab)}/ (${Object.keys(files).length} files)`)
  console.log(`REST path: /${n.domainsKebab} (POST to create, GET /:${n.domain}Id to look up, POST /:${n.domain}Id/cancel to cancel)`)
  console.log('')
  console.log('Note: a naive pluralization rule (+s / +es / y->ies) was used — for domains with irregular plurals,')
  console.log(`  you may need to manually adjust names like find${n.Domains}/${n.domains}.`)

  if (shouldWire) {
    wireAppModule(path.join(targetSrcDir, 'app-module.ts'), n)
  } else {
    printAppModuleSnippet(n)
  }

  console.log('Next: verify with bash harness.sh <projectRoot>.')
}

main()
