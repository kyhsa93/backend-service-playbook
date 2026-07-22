import {
  BadRequestException, Body, Controller, Get, HttpCode,
  Logger, NotFoundException, Param, Post, Query
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
import { CloseAccountCommand } from '@/account/application/command/close-account-command'
import { CreateAccountCommand } from '@/account/application/command/create-account-command'
import { DepositCommand } from '@/account/application/command/deposit-command'
import { ReactivateAccountCommand } from '@/account/application/command/reactivate-account-command'
import { SuspendAccountCommand } from '@/account/application/command/suspend-account-command'
import { TransferCommand } from '@/account/application/command/transfer-command'
import { TransferResult } from '@/account/application/command/transfer-result'
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
import { TransferRequestBody } from '@/account/interface/dto/transfer-request-body'
import { TransferResponseBody } from '@/account/interface/dto/transfer-response-body'
import { WithdrawRequestBody } from '@/account/interface/dto/withdraw-request-body'
import { WithdrawResponseBody } from '@/account/interface/dto/withdraw-response-body'
import { AccountErrorCode as ErrorCode } from '@/account/account-error-code'
import { AccountErrorMessage } from '@/account/account-error-message'

@Controller()
@ApiTags('Account')
@ApiBearerAuth('token')
@ApiUnauthorizedResponse({ description: 'The bearer token is missing, malformed, or invalid.', type: ErrorResponseBody })
@Authenticated()
export class AccountController {
  private readonly logger = new Logger(AccountController.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Post('/accounts')
  @ApiOperation({
    operationId: 'createAccount',
    summary: 'Open a new account',
    description: 'Opens a new account for the authenticated requester with a 0 balance in the given currency.'
  })
  @ApiCreatedResponse({ description: 'The account was created.', type: CreateAccountResponseBody })
  @ApiBadRequestResponse({ description: 'Request validation failed (`VALIDATION_FAILED`) — e.g. an invalid email or missing currency.', type: ErrorResponseBody })
  public async createAccount(
    @Body() body: CreateAccountRequestBody
  ): Promise<CreateAccountResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
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
  @ApiOperation({
    operationId: 'deposit',
    summary: 'Deposit money into an account',
    description: 'Credits the given amount to the account and records a `DEPOSIT` transaction.'
  })
  @ApiCreatedResponse({ description: 'The deposit succeeded.', type: DepositResponseBody })
  @ApiBadRequestResponse({
    description: 'One of: the amount is not a positive integer (`INVALID_AMOUNT`), the account is not active (`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`), or request validation failed (`VALIDATION_FAILED`).',
    type: ErrorResponseBody
  })
  @ApiNotFoundResponse({ description: 'No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async deposit(
    @Param('accountId') accountId: string,
    @Body() body: DepositRequestBody
  ): Promise<DepositResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
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
          [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['The amount must be greater than 0.'], BadRequestException, ErrorCode.INVALID_AMOUNT],
          [AccountErrorMessage['Only an active account can accept a deposit.'], BadRequestException, ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT]
        ])
      })
  }

  @Post('/accounts/:accountId/withdraw')
  @ApiOperation({
    operationId: 'withdraw',
    summary: 'Withdraw money from an account',
    description: 'Debits the given amount from the account and records a `WITHDRAWAL` transaction.'
  })
  @ApiCreatedResponse({ description: 'The withdrawal succeeded.', type: WithdrawResponseBody })
  @ApiBadRequestResponse({
    description: 'One of: the amount is not a positive integer (`INVALID_AMOUNT`), the account is not active (`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`), the balance is insufficient (`INSUFFICIENT_BALANCE`), or request validation failed (`VALIDATION_FAILED`).',
    type: ErrorResponseBody
  })
  @ApiNotFoundResponse({ description: 'No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async withdraw(
    @Param('accountId') accountId: string,
    @Body() body: WithdrawRequestBody
  ): Promise<WithdrawResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
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
          [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['The amount must be greater than 0.'], BadRequestException, ErrorCode.INVALID_AMOUNT],
          [AccountErrorMessage['Only an active account can make a withdrawal.'], BadRequestException, ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT],
          [AccountErrorMessage['Insufficient balance.'], BadRequestException, ErrorCode.INSUFFICIENT_BALANCE]
        ])
      })
  }

  @Post('/accounts/:accountId/transfer')
  @ApiOperation({
    operationId: 'transfer',
    summary: 'Transfer money to another account',
    description: 'Atomically debits the source account and credits the target account with the given amount, recording one `WITHDRAWAL` and one `DEPOSIT` transaction.'
  })
  @ApiCreatedResponse({ description: 'The transfer succeeded.', type: TransferResponseBody })
  @ApiBadRequestResponse({
    description: 'One of: the amount is not a positive integer (`INVALID_AMOUNT`), the source and target accounts are the same (`TRANSFER_SAME_ACCOUNT`), either account is not active (`WITHDRAW_REQUIRES_ACTIVE_ACCOUNT`/`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`), the currencies do not match (`CURRENCY_MISMATCH`), the balance is insufficient (`INSUFFICIENT_BALANCE`), or request validation failed (`VALIDATION_FAILED`).',
    type: ErrorResponseBody
  })
  @ApiNotFoundResponse({ description: 'No account exists with the given source or target `accountId` (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async transfer(
    @Param('accountId') accountId: string,
    @Body() body: TransferRequestBody
  ): Promise<TransferResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.commandBus.execute<TransferCommand, TransferResult>(
      new TransferCommand({ sourceAccountId: accountId, targetAccountId: body.targetAccountId, amount: body.amount, requesterId })
    )
      .then((result) => ({
        transferId: result.transferId,
        sourceTransaction: {
          transactionId: result.sourceTransaction.transactionId,
          accountId: result.sourceTransaction.accountId,
          type: result.sourceTransaction.type,
          amount: { amount: result.sourceTransaction.amount.amount, currency: result.sourceTransaction.amount.currency },
          createdAt: result.sourceTransaction.createdAt
        },
        targetTransaction: {
          transactionId: result.targetTransaction.transactionId,
          accountId: result.targetTransaction.accountId,
          type: result.targetTransaction.type,
          amount: { amount: result.targetTransaction.amount.amount, currency: result.targetTransaction.amount.currency },
          createdAt: result.targetTransaction.createdAt
        }
      }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['The amount must be greater than 0.'], BadRequestException, ErrorCode.INVALID_AMOUNT],
          [AccountErrorMessage['The withdrawal account and deposit account cannot be the same.'], BadRequestException, ErrorCode.TRANSFER_SAME_ACCOUNT],
          [AccountErrorMessage['Only an active account can make a withdrawal.'], BadRequestException, ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT],
          [AccountErrorMessage['Only an active account can accept a deposit.'], BadRequestException, ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT],
          [AccountErrorMessage['The currencies do not match.'], BadRequestException, ErrorCode.CURRENCY_MISMATCH],
          [AccountErrorMessage['Insufficient balance.'], BadRequestException, ErrorCode.INSUFFICIENT_BALANCE]
        ])
      })
  }

  @Post('/accounts/:accountId/suspend')
  @HttpCode(204)
  @ApiOperation({
    operationId: 'suspendAccount',
    summary: 'Suspend an account',
    description: 'Suspends an active account, blocking further deposits/withdrawals/transfers until it is reactivated.'
  })
  @ApiNoContentResponse({ description: 'The account was suspended.' })
  @ApiBadRequestResponse({ description: 'Only an active account can be suspended (`SUSPEND_REQUIRES_ACTIVE_ACCOUNT`).', type: ErrorResponseBody })
  @ApiNotFoundResponse({ description: 'No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async suspendAccount(
    @Param('accountId') accountId: string
  ): Promise<void> {
    const requesterId = UserContextStore.getRequesterId()
    return this.commandBus.execute(new SuspendAccountCommand({ accountId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['Only an active account can be suspended.'], BadRequestException, ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT]
        ])
      })
  }

  @Post('/accounts/:accountId/reactivate')
  @HttpCode(204)
  @ApiOperation({
    operationId: 'reactivateAccount',
    summary: 'Reactivate a suspended account',
    description: 'Moves a suspended account back to active, restoring its ability to accept deposits/withdrawals/transfers.'
  })
  @ApiNoContentResponse({ description: 'The account was reactivated.' })
  @ApiBadRequestResponse({ description: 'Only a suspended account can be reactivated (`REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT`).', type: ErrorResponseBody })
  @ApiNotFoundResponse({ description: 'No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async reactivateAccount(
    @Param('accountId') accountId: string
  ): Promise<void> {
    const requesterId = UserContextStore.getRequesterId()
    return this.commandBus.execute(new ReactivateAccountCommand({ accountId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['Only a suspended account can be reactivated.'], BadRequestException, ErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT]
        ])
      })
  }

  @Post('/accounts/:accountId/close')
  @HttpCode(204)
  @ApiOperation({
    operationId: 'closeAccount',
    summary: 'Close an account',
    description: 'Permanently closes an account. The balance must be exactly 0 first (withdraw or transfer out any remaining funds).'
  })
  @ApiNoContentResponse({ description: 'The account was closed.' })
  @ApiBadRequestResponse({
    description: 'One of: the account is already closed (`ACCOUNT_ALREADY_CLOSED`), or the balance is not 0 (`ACCOUNT_BALANCE_NOT_ZERO`).',
    type: ErrorResponseBody
  })
  @ApiNotFoundResponse({ description: 'No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async closeAccount(
    @Param('accountId') accountId: string
  ): Promise<void> {
    const requesterId = UserContextStore.getRequesterId()
    return this.commandBus.execute(new CloseAccountCommand({ accountId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND],
          [AccountErrorMessage['The account is already closed.'], BadRequestException, ErrorCode.ACCOUNT_ALREADY_CLOSED],
          [AccountErrorMessage['An account with a non-zero balance cannot be closed.'], BadRequestException, ErrorCode.ACCOUNT_BALANCE_NOT_ZERO]
        ])
      })
  }

  @Get('/accounts/:accountId')
  @ApiOperation({
    operationId: 'getAccount',
    summary: 'Look up an account',
    description: 'Returns the account only if it belongs to the authenticated requester.'
  })
  @ApiOkResponse({ description: 'The account was found.', type: GetAccountResponseBody })
  @ApiNotFoundResponse({ description: 'No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async getAccount(
    @Param() param: GetAccountRequestParam
  ): Promise<GetAccountResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.queryBus.execute(new GetAccountQuery({ accountId: param.accountId, requesterId })).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND]
      ])
    })
  }

  @Get('/accounts/:accountId/transactions')
  @ApiOperation({
    operationId: 'getTransactions',
    summary: 'List an account\'s transaction history',
    description: 'Returns the account\'s deposit/withdrawal/interest transactions, newest first, paginated with `page`/`take`.'
  })
  @ApiOkResponse({ description: 'The transaction history was found.', type: GetTransactionsResponseBody })
  @ApiBadRequestResponse({ description: 'Request validation failed (`VALIDATION_FAILED`) — e.g. `page`/`take` out of range.', type: ErrorResponseBody })
  @ApiNotFoundResponse({ description: 'No account exists with the given `accountId` for this requester (`ACCOUNT_NOT_FOUND`).', type: ErrorResponseBody })
  public async getTransactions(
    @Param() param: GetTransactionsRequestParam,
    @Query() querystring: GetTransactionsRequestQuerystring
  ): Promise<GetTransactionsResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.queryBus.execute(
      new GetTransactionsQuery({ ...querystring, accountId: param.accountId, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [AccountErrorMessage['Account not found.'], NotFoundException, ErrorCode.ACCOUNT_NOT_FOUND]
      ])
    })
  }
}
