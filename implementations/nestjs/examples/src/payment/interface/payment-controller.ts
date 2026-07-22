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
import { CancelPaymentCommand } from '@/payment/application/command/cancel-payment-command'
import { CreatePaymentCommand } from '@/payment/application/command/create-payment-command'
import { RequestRefundCommand } from '@/payment/application/command/request-refund-command'
import { Payment } from '@/payment/domain/payment'
import { Refund } from '@/payment/domain/refund'
import { GetPaymentQuery } from '@/payment/application/query/get-payment-query'
import { GetPaymentsQuery } from '@/payment/application/query/get-payments-query'
import { GetRefundsQuery } from '@/payment/application/query/get-refunds-query'
import { GetPaymentResult, GetPaymentsResult } from '@/payment/application/query/payment-result'
import { GetRefundsResult } from '@/payment/application/query/refund-result'
import { CancelPaymentRequestBody } from '@/payment/interface/dto/cancel-payment-request-body'
import { CreatePaymentRequestBody } from '@/payment/interface/dto/create-payment-request-body'
import { CreatePaymentResponseBody } from '@/payment/interface/dto/create-payment-response-body'
import { GetPaymentRequestParam } from '@/payment/interface/dto/get-payment-request-param'
import { GetPaymentResponseBody } from '@/payment/interface/dto/get-payment-response-body'
import { GetPaymentsRequestQuerystring } from '@/payment/interface/dto/get-payments-request-querystring'
import { GetPaymentsResponseBody } from '@/payment/interface/dto/get-payments-response-body'
import { GetRefundsRequestParam } from '@/payment/interface/dto/get-refunds-request-param'
import { GetRefundsRequestQuerystring } from '@/payment/interface/dto/get-refunds-request-querystring'
import { GetRefundsResponseBody } from '@/payment/interface/dto/get-refunds-response-body'
import { RequestRefundRequestBody } from '@/payment/interface/dto/request-refund-request-body'
import { RequestRefundResponseBody } from '@/payment/interface/dto/request-refund-response-body'
import { PaymentErrorCode as ErrorCode } from '@/payment/payment-error-code'
import { PaymentErrorMessage } from '@/payment/payment-error-message'

@Controller()
@ApiTags('Payment')
@ApiBearerAuth('token')
@ApiUnauthorizedResponse({ description: 'The bearer token is missing, malformed, or invalid.', type: ErrorResponseBody })
@Authenticated()
export class PaymentController {
  private readonly logger = new Logger(PaymentController.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Post('/payments')
  @ApiOperation({
    operationId: 'createPayment',
    summary: 'Create a payment',
    description: 'Charges an active card linked to an active account with sufficient balance. The account balance is debited asynchronously once the payment completes.'
  })
  @ApiCreatedResponse({ description: 'The payment was created.', type: CreatePaymentResponseBody })
  @ApiBadRequestResponse({
    description: 'One of: the card is not active (`PAYMENT_REQUIRES_ACTIVE_CARD`), the linked account is not active (`PAYMENT_REQUIRES_ACTIVE_ACCOUNT`), the account balance is insufficient (`INSUFFICIENT_BALANCE`), or request validation failed (`VALIDATION_FAILED`).',
    type: ErrorResponseBody
  })
  @ApiNotFoundResponse({
    description: 'One of: no card exists with the given `cardId` (`LINKED_CARD_NOT_FOUND`), or the card\'s linked account could not be found (`LINKED_ACCOUNT_NOT_FOUND`).',
    type: ErrorResponseBody
  })
  public async createPayment(
    @Body() body: CreatePaymentRequestBody
  ): Promise<CreatePaymentResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.commandBus.execute<CreatePaymentCommand, Payment>(new CreatePaymentCommand({ ...body, requesterId }))
      .then((payment) => this.toPaymentResponse(payment))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [PaymentErrorMessage['The card to link could not be found.'], NotFoundException, ErrorCode.LINKED_CARD_NOT_FOUND],
          [PaymentErrorMessage['Only an active card can be used for payment.'], BadRequestException, ErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD],
          [PaymentErrorMessage['The linked account could not be found.'], NotFoundException, ErrorCode.LINKED_ACCOUNT_NOT_FOUND],
          [PaymentErrorMessage['Only an active account can be used for payment.'], BadRequestException, ErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT],
          [PaymentErrorMessage['Payment cannot be made due to insufficient account balance.'], BadRequestException, ErrorCode.INSUFFICIENT_BALANCE]
        ])
      })
  }

  @Post('/payments/:paymentId/cancel')
  @HttpCode(204)
  @ApiOperation({
    operationId: 'cancelPayment',
    summary: 'Cancel a payment',
    description: 'Cancels a completed payment before it is refunded.'
  })
  @ApiNoContentResponse({ description: 'The payment was cancelled.' })
  @ApiBadRequestResponse({
    description: 'One of: only a completed payment can be cancelled (`PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT`), or request validation failed (`VALIDATION_FAILED`).',
    type: ErrorResponseBody
  })
  @ApiNotFoundResponse({ description: 'No payment exists with the given `paymentId` (`PAYMENT_NOT_FOUND`).', type: ErrorResponseBody })
  public async cancelPayment(
    @Param('paymentId') paymentId: string,
    @Body() body: CancelPaymentRequestBody
  ): Promise<void> {
    const requesterId = UserContextStore.getRequesterId()
    return this.commandBus.execute(new CancelPaymentCommand({ ...body, paymentId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [PaymentErrorMessage['Payment not found.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND],
          [PaymentErrorMessage['Only a completed payment can be cancelled.'], BadRequestException, ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT]
        ])
      })
  }

  @Get('/payments/:paymentId')
  @ApiOperation({
    operationId: 'getPayment',
    summary: 'Look up a payment',
    description: 'Returns the payment only if it belongs to the authenticated requester.'
  })
  @ApiOkResponse({ description: 'The payment was found.', type: GetPaymentResponseBody })
  @ApiNotFoundResponse({ description: 'No payment exists with the given `paymentId` for this requester (`PAYMENT_NOT_FOUND`).', type: ErrorResponseBody })
  public async getPayment(
    @Param() param: GetPaymentRequestParam
  ): Promise<GetPaymentResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.queryBus.execute<GetPaymentQuery, GetPaymentResult>(
      new GetPaymentQuery({ paymentId: param.paymentId, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [PaymentErrorMessage['Payment not found.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND]
      ])
    })
  }

  @Get('/payments')
  @ApiOperation({
    operationId: 'getPayments',
    summary: "List the requester's payments",
    description: 'Returns the authenticated requester\'s payments, newest first, paginated with `page`/`take`.'
  })
  @ApiOkResponse({ description: 'The payment list was found.', type: GetPaymentsResponseBody })
  @ApiBadRequestResponse({ description: 'Request validation failed (`VALIDATION_FAILED`) — e.g. `page`/`take` out of range.', type: ErrorResponseBody })
  public async getPayments(
    @Query() querystring: GetPaymentsRequestQuerystring
  ): Promise<GetPaymentsResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.queryBus.execute<GetPaymentsQuery, GetPaymentsResult>(
      new GetPaymentsQuery({ ...querystring, requesterId })
    )
  }

  @Post('/payments/:paymentId/refunds')
  @ApiOperation({
    operationId: 'requestRefund',
    summary: 'Request a refund',
    description: 'Requests a refund for a payment. Eligibility is judged synchronously (`APPROVED`/`REJECTED`); an approved refund is credited back to the account asynchronously.'
  })
  @ApiCreatedResponse({ description: 'The refund request was recorded (its `status` may still be `REJECTED` — check the response body, not just the HTTP status).', type: RequestRefundResponseBody })
  @ApiBadRequestResponse({ description: 'Request validation failed (`VALIDATION_FAILED`) — e.g. a non-positive amount or missing reason.', type: ErrorResponseBody })
  @ApiNotFoundResponse({ description: 'No payment exists with the given `paymentId` (`PAYMENT_NOT_FOUND`).', type: ErrorResponseBody })
  public async requestRefund(
    @Param('paymentId') paymentId: string,
    @Body() body: RequestRefundRequestBody
  ): Promise<RequestRefundResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.commandBus.execute<RequestRefundCommand, Refund>(
      new RequestRefundCommand({ ...body, paymentId, requesterId })
    )
      .then((refund) => ({
        refundId: refund.refundId,
        paymentId: refund.paymentId,
        amount: refund.amount,
        reason: refund.reason,
        status: refund.status,
        decisionNote: refund.decisionNote,
        createdAt: refund.createdAt
      }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [PaymentErrorMessage['Payment not found.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND]
        ])
      })
  }

  @Get('/payments/:paymentId/refunds')
  @ApiOperation({
    operationId: 'getRefunds',
    summary: "List a payment's refunds",
    description: 'Returns the refunds requested against a payment, newest first, paginated with `page`/`take`.'
  })
  @ApiOkResponse({ description: 'The refund list was found.', type: GetRefundsResponseBody })
  @ApiBadRequestResponse({ description: 'Request validation failed (`VALIDATION_FAILED`) — e.g. `page`/`take` out of range.', type: ErrorResponseBody })
  @ApiNotFoundResponse({ description: 'No payment exists with the given `paymentId` (`PAYMENT_NOT_FOUND`).', type: ErrorResponseBody })
  public async getRefunds(
    @Param() param: GetRefundsRequestParam,
    @Query() querystring: GetRefundsRequestQuerystring
  ): Promise<GetRefundsResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    return this.queryBus.execute<GetRefundsQuery, GetRefundsResult>(
      new GetRefundsQuery({ ...querystring, paymentId: param.paymentId, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [PaymentErrorMessage['Payment not found.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND]
      ])
    })
  }

  private toPaymentResponse(payment: Payment): GetPaymentResult {
    return {
      paymentId: payment.paymentId,
      cardId: payment.cardId,
      accountId: payment.accountId,
      ownerId: payment.ownerId,
      amount: payment.amount,
      status: payment.status,
      createdAt: payment.createdAt
    }
  }
}
