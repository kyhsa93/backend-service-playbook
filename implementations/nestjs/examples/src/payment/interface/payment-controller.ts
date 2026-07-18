import {
  BadRequestException, Body, Controller, Get, HttpCode,
  Logger, NotFoundException, Param, Post, Query, Req, UseGuards
} from '@nestjs/common'
import { ApiBearerAuth, ApiCreatedResponse, ApiNoContentResponse, ApiOkResponse, ApiOperation, ApiTags } from '@nestjs/swagger'
import { CommandBus, QueryBus } from '@nestjs/cqrs'
import { Request } from 'express'

import { generateErrorResponse } from '@/common/generate-error-response'
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
import { AuthGuard } from '@/auth/auth.guard'

type AuthenticatedRequest = Request & { user: { userId: string } }

@Controller()
@ApiTags('Payment')
@ApiBearerAuth('token')
@UseGuards(AuthGuard)
export class PaymentController {
  private readonly logger = new Logger(PaymentController.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Post('/payments')
  @ApiOperation({ operationId: 'createPayment' })
  @ApiCreatedResponse({ type: CreatePaymentResponseBody })
  public async createPayment(
    @Req() req: AuthenticatedRequest,
    @Body() body: CreatePaymentRequestBody
  ): Promise<CreatePaymentResponseBody> {
    const requesterId = req.user.userId
    return this.commandBus.execute<CreatePaymentCommand, Payment>(new CreatePaymentCommand({ ...body, requesterId }))
      .then((payment) => this.toPaymentResponse(payment))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [PaymentErrorMessage['연결할 카드를 찾을 수 없습니다.'], NotFoundException, ErrorCode.LINKED_CARD_NOT_FOUND],
          [PaymentErrorMessage['활성 상태의 카드로만 결제할 수 있습니다.'], BadRequestException, ErrorCode.PAYMENT_REQUIRES_ACTIVE_CARD],
          [PaymentErrorMessage['연결된 계좌를 찾을 수 없습니다.'], NotFoundException, ErrorCode.LINKED_ACCOUNT_NOT_FOUND],
          [PaymentErrorMessage['활성 상태의 계좌로만 결제할 수 있습니다.'], BadRequestException, ErrorCode.PAYMENT_REQUIRES_ACTIVE_ACCOUNT],
          [PaymentErrorMessage['계좌 잔액이 부족하여 결제할 수 없습니다.'], BadRequestException, ErrorCode.INSUFFICIENT_BALANCE]
        ])
      })
  }

  @Post('/payments/:paymentId/cancel')
  @HttpCode(204)
  @ApiOperation({ operationId: 'cancelPayment' })
  @ApiNoContentResponse()
  public async cancelPayment(
    @Req() req: AuthenticatedRequest,
    @Param('paymentId') paymentId: string,
    @Body() body: CancelPaymentRequestBody
  ): Promise<void> {
    const requesterId = req.user.userId
    return this.commandBus.execute(new CancelPaymentCommand({ ...body, paymentId, requesterId }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [PaymentErrorMessage['결제를 찾을 수 없습니다.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND],
          [PaymentErrorMessage['완료된 결제만 취소할 수 있습니다.'], BadRequestException, ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT]
        ])
      })
  }

  @Get('/payments/:paymentId')
  @ApiOperation({ operationId: 'getPayment' })
  @ApiOkResponse({ type: GetPaymentResponseBody })
  public async getPayment(
    @Req() req: AuthenticatedRequest,
    @Param() param: GetPaymentRequestParam
  ): Promise<GetPaymentResponseBody> {
    const requesterId = req.user.userId
    return this.queryBus.execute<GetPaymentQuery, GetPaymentResult>(
      new GetPaymentQuery({ paymentId: param.paymentId, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [PaymentErrorMessage['결제를 찾을 수 없습니다.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND]
      ])
    })
  }

  @Get('/payments')
  @ApiOperation({ operationId: 'getPayments' })
  @ApiOkResponse({ type: GetPaymentsResponseBody })
  public async getPayments(
    @Req() req: AuthenticatedRequest,
    @Query() querystring: GetPaymentsRequestQuerystring
  ): Promise<GetPaymentsResponseBody> {
    const requesterId = req.user.userId
    return this.queryBus.execute<GetPaymentsQuery, GetPaymentsResult>(
      new GetPaymentsQuery({ ...querystring, requesterId })
    )
  }

  @Post('/payments/:paymentId/refunds')
  @ApiOperation({ operationId: 'requestRefund' })
  @ApiCreatedResponse({ type: RequestRefundResponseBody })
  public async requestRefund(
    @Req() req: AuthenticatedRequest,
    @Param('paymentId') paymentId: string,
    @Body() body: RequestRefundRequestBody
  ): Promise<RequestRefundResponseBody> {
    const requesterId = req.user.userId
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
          [PaymentErrorMessage['결제를 찾을 수 없습니다.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND]
        ])
      })
  }

  @Get('/payments/:paymentId/refunds')
  @ApiOperation({ operationId: 'getRefunds' })
  @ApiOkResponse({ type: GetRefundsResponseBody })
  public async getRefunds(
    @Req() req: AuthenticatedRequest,
    @Param() param: GetRefundsRequestParam,
    @Query() querystring: GetRefundsRequestQuerystring
  ): Promise<GetRefundsResponseBody> {
    const requesterId = req.user.userId
    return this.queryBus.execute<GetRefundsQuery, GetRefundsResult>(
      new GetRefundsQuery({ ...querystring, paymentId: param.paymentId, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [PaymentErrorMessage['결제를 찾을 수 없습니다.'], NotFoundException, ErrorCode.PAYMENT_NOT_FOUND]
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
