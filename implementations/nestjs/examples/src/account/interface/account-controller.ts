import {
  BadRequestException, Body, Controller, Get, HttpCode,
  Logger, NotFoundException, Param, Post, Query, Req, UseGuards
} from '@nestjs/common'
import { ApiBearerAuth, ApiCreatedResponse, ApiNoContentResponse, ApiOkResponse, ApiOperation, ApiTags } from '@nestjs/swagger'
import { CommandBus, QueryBus } from '@nestjs/cqrs'
import { Request } from 'express'

import { generateErrorResponse } from '@/common/generate-error-response'
import { CloseAccountCommand } from '@/account/application/command/close-account-command'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { DepositCommand } from '@/account/application/command/deposit-command'
import { ReactivateAccountCommand } from '@/account/application/command/reactivate-account-command'
import { SuspendAccountCommand } from '@/account/application/command/suspend-account-command'
import { WithdrawCommand } from '@/account/application/command/withdraw-command'
import { Account } from '@/account/domain/account'
import { Transaction } from '@/account/domain/transaction'
import { GetAccountQuery } from '@/account/application/query/get-account-query'
import { GetTransactionsQuery } from '@/account/application/query/get-transactions-query'
import { CreateAccountRequestBody } from '@/account/interface/dto/create-account-request-body'
import { CreateAccountResponseBody } from '@/account/interface/dto/create-account-response-body'
import { DepositRequestBody } from '@/account/interface/dto/deposit-request-body'
import { DepositResponseBody } from '@/account/interface/dto/deposit-response-body'
import { GetAccountRequestParam } from '@/account/interface/dto/get-account-request-param'
import { GetAccountResponseBody } from '@/account/interface/dto/get-account-response-body'
import { GetTransactionsRequestParam } from '@/account/interface/dto/get-transactions-request-param'
import { GetTransactionsRequestQuerystring } from '@/account/interface/dto/get-transactions-request-querystring'
import { GetTransactionsResponseBody } from '@/account/interface/dto/get-transactions-response-body'
import { WithdrawRequestBody } from '@/account/interface/dto/withdraw-request-body'
import { WithdrawResponseBody } from '@/account/interface/dto/withdraw-response-body'
import { AccountErrorCode as ErrorCode } from '@/account/account-error-code'
import { AccountErrorMessage } from '@/account/account-error-message'
import { AuthGuard } from '@/auth/auth.guard'

type AuthenticatedRequest = Request & { user: { userId: string } }

@Controller()
@ApiTags('Account')
@ApiBearerAuth('token')
@UseGuards(AuthGuard)
export class AccountController {
  private readonly logger = new Logger(AccountController.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Post('/accounts')
  @ApiOperation({ operationId: 'createAccount' })
  @ApiCreatedResponse({ type: CreateAccountResponseBody })
  public async createAccount(
    @Req() req: AuthenticatedRequest,
    @Body() body: CreateAccountRequestBody
  ): Promise<CreateAccountResponseBody> {
    const requesterId = req.user.userId
    return this.commandBus.execute<CreateAccountCommand, Account>(new CreateAccountCommand({ ...body, requesterId }))
      .then((account) => ({
        accountId: account.accountId,
        ownerId: account.ownerId,
        email: account.email,
        balance: { amount: account.balance.amount, currency: account.balance.currency },
        status: account.status,
        createdAt: account.createdAt
      }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [])
      })
  }

  @Post('/accounts/:accountId/deposit')
  @ApiOperation({ operationId: 'deposit' })
  @ApiCreatedResponse({ type: DepositResponseBody })
  public async deposit(
    @Req() req: AuthenticatedRequest,
    @Param('accountId') accountId: string,
    @Body() body: DepositRequestBody
  ): Promise<DepositResponseBody> {
    const requesterId = req.user.userId
    return this.commandBus.execute<DepositCommand, Transaction>(new DepositCommand({ ...body, accountId, requesterId }))
      .then((transaction) => ({
        transactionId: transaction.transactionId,
        accountId: transaction.accountId,
        type: transaction.type,
        amount: { amount: transaction.amount.amount, currency: transaction.amount.currency },
        createdAt: transaction.createdAt
      }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['금액은 0보다 커야 합니다.'], BadRequestException, ErrorCode.INVALID_AMOUNT],
          [AccountErrorMessage['활성 상태의 계좌만 입금할 수 있습니다.'], BadRequestException, ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT]
        ])
      })
  }

  @Post('/accounts/:accountId/withdraw')
  @ApiOperation({ operationId: 'withdraw' })
  @ApiCreatedResponse({ type: WithdrawResponseBody })
  public async withdraw(
    @Req() req: AuthenticatedRequest,
    @Param('accountId') accountId: string,
    @Body() body: WithdrawRequestBody
  ): Promise<WithdrawResponseBody> {
    const requesterId = req.user.userId
    return this.commandBus.execute<WithdrawCommand, Transaction>(new WithdrawCommand({ ...body, accountId, requesterId }))
      .then((transaction) => ({
        transactionId: transaction.transactionId,
        accountId: transaction.accountId,
        type: transaction.type,
        amount: { amount: transaction.amount.amount, currency: transaction.amount.currency },
        createdAt: transaction.createdAt
      }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['금액은 0보다 커야 합니다.'], BadRequestException, ErrorCode.INVALID_AMOUNT],
          [AccountErrorMessage['활성 상태의 계좌만 출금할 수 있습니다.'], BadRequestException, ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT],
          [AccountErrorMessage['잔액이 부족합니다.'], BadRequestException, ErrorCode.INSUFFICIENT_BALANCE]
        ])
      })
  }

  @Post('/accounts/:accountId/suspend')
  @HttpCode(204)
  @ApiOperation({ operationId: 'suspendAccount' })
  @ApiNoContentResponse()
  public async suspendAccount(
    @Req() req: AuthenticatedRequest,
    @Param('accountId') accountId: string
  ): Promise<void> {
    const requesterId = req.user.userId
    return this.commandBus.execute(new SuspendAccountCommand({ accountId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['활성 상태의 계좌만 정지할 수 있습니다.'], BadRequestException, ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT]
        ])
      })
  }

  @Post('/accounts/:accountId/reactivate')
  @HttpCode(204)
  @ApiOperation({ operationId: 'reactivateAccount' })
  @ApiNoContentResponse()
  public async reactivateAccount(
    @Req() req: AuthenticatedRequest,
    @Param('accountId') accountId: string
  ): Promise<void> {
    const requesterId = req.user.userId
    return this.commandBus.execute(new ReactivateAccountCommand({ accountId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['정지 상태의 계좌만 재개할 수 있습니다.'], BadRequestException, ErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT]
        ])
      })
  }

  @Post('/accounts/:accountId/close')
  @HttpCode(204)
  @ApiOperation({ operationId: 'closeAccount' })
  @ApiNoContentResponse()
  public async closeAccount(
    @Req() req: AuthenticatedRequest,
    @Param('accountId') accountId: string
  ): Promise<void> {
    const requesterId = req.user.userId
    return this.commandBus.execute(new CloseAccountCommand({ accountId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['이미 종료된 계좌입니다.'], BadRequestException, ErrorCode.ACCOUNT_ALREADY_CLOSED],
          [AccountErrorMessage['잔액이 0이 아닌 계좌는 종료할 수 없습니다.'], BadRequestException, ErrorCode.ACCOUNT_BALANCE_NOT_ZERO]
        ])
      })
  }

  @Get('/accounts/:accountId')
  @ApiOperation({ operationId: 'getAccount' })
  @ApiOkResponse({ type: GetAccountResponseBody })
  public async getAccount(
    @Req() req: AuthenticatedRequest,
    @Param() param: GetAccountRequestParam
  ): Promise<GetAccountResponseBody> {
    const requesterId = req.user.userId
    return this.queryBus.execute(new GetAccountQuery({ accountId: param.accountId, requesterId })).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND]
      ])
    })
  }

  @Get('/accounts/:accountId/transactions')
  @ApiOperation({ operationId: 'getTransactions' })
  @ApiOkResponse({ type: GetTransactionsResponseBody })
  public async getTransactions(
    @Req() req: AuthenticatedRequest,
    @Param() param: GetTransactionsRequestParam,
    @Query() querystring: GetTransactionsRequestQuerystring
  ): Promise<GetTransactionsResponseBody> {
    const requesterId = req.user.userId
    return this.queryBus.execute(
      new GetTransactionsQuery({ ...querystring, accountId: param.accountId, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND]
      ])
    })
  }
}
