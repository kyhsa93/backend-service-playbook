import {
  BadRequestException, Body, Controller, Get, Headers, HttpCode,
  Logger, NotFoundException, Param, Post, Query
} from '@nestjs/common'
import { ApiCreatedResponse, ApiHeader, ApiNoContentResponse, ApiOkResponse, ApiOperation, ApiTags } from '@nestjs/swagger'

import { generateErrorResponse } from '@/common/generate-error-response'
import { AccountCommandService } from '@/account/application/command/account-command-service'
import { CloseAccountCommand } from '@/account/application/command/close-account-command'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { DepositCommand } from '@/account/application/command/deposit-command'
import { ReactivateAccountCommand } from '@/account/application/command/reactivate-account-command'
import { SuspendAccountCommand } from '@/account/application/command/suspend-account-command'
import { WithdrawCommand } from '@/account/application/command/withdraw-command'
import { AccountQueryService } from '@/account/application/query/account-query-service'
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

@Controller()
@ApiTags('Account')
@ApiHeader({ name: 'X-User-Id', required: true })
export class AccountController {
  private readonly logger = new Logger(AccountController.name)

  constructor(
    private readonly accountCommandService: AccountCommandService,
    private readonly accountQueryService: AccountQueryService
  ) {}

  @Post('/accounts')
  @ApiOperation({ operationId: 'createAccount' })
  @ApiCreatedResponse({ type: CreateAccountResponseBody })
  public async createAccount(
    @Headers('x-user-id') requesterId: string,
    @Body() body: CreateAccountRequestBody
  ): Promise<CreateAccountResponseBody> {
    return this.accountCommandService.createAccount(new CreateAccountCommand({ ...body, requesterId }))
      .then((account) => ({
        accountId: account.accountId,
        ownerId: account.ownerId,
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
    @Headers('x-user-id') requesterId: string,
    @Param('accountId') accountId: string,
    @Body() body: DepositRequestBody
  ): Promise<DepositResponseBody> {
    return this.accountCommandService.deposit(new DepositCommand({ ...body, accountId, requesterId }))
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
    @Headers('x-user-id') requesterId: string,
    @Param('accountId') accountId: string,
    @Body() body: WithdrawRequestBody
  ): Promise<WithdrawResponseBody> {
    return this.accountCommandService.withdraw(new WithdrawCommand({ ...body, accountId, requesterId }))
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
    @Headers('x-user-id') requesterId: string,
    @Param('accountId') accountId: string
  ): Promise<void> {
    return this.accountCommandService.suspendAccount(new SuspendAccountCommand({ accountId, requesterId }))
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
    @Headers('x-user-id') requesterId: string,
    @Param('accountId') accountId: string
  ): Promise<void> {
    return this.accountCommandService.reactivateAccount(new ReactivateAccountCommand({ accountId, requesterId }))
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
    @Headers('x-user-id') requesterId: string,
    @Param('accountId') accountId: string
  ): Promise<void> {
    return this.accountCommandService.closeAccount(new CloseAccountCommand({ accountId, requesterId }))
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
    @Headers('x-user-id') requesterId: string,
    @Param() param: GetAccountRequestParam
  ): Promise<GetAccountResponseBody> {
    return this.accountQueryService.getAccount({ accountId: param.accountId, requesterId }).catch((error) => {
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
    @Headers('x-user-id') requesterId: string,
    @Param() param: GetTransactionsRequestParam,
    @Query() querystring: GetTransactionsRequestQuerystring
  ): Promise<GetTransactionsResponseBody> {
    return this.accountQueryService.getTransactions({
      accountId: param.accountId,
      requesterId,
      page: querystring.page,
      take: querystring.take
    }).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [AccountErrorMessage['계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND]
      ])
    })
  }
}
