import {
  BadRequestException, Body, Controller, Delete, Get, HttpCode,
  Logger, NotFoundException, Param, Post, Query, UseGuards, UseInterceptors
} from '@nestjs/common'
import {
  ApiBearerAuth, ApiCreatedResponse, ApiNoContentResponse,
  ApiOkResponse, ApiOperation, ApiTags
} from '@nestjs/swagger'

import { AuthGuard } from '@/auth/auth.guard'
import { generateErrorResponse } from '@/common/generate-error-response'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { CancelOrderCommand } from '@/order/application/command/cancel-order-command'
import { CreateOrderCommand } from '@/order/application/command/create-order-command'
import { OrderCommandService } from '@/order/application/command/order-command-service'
import { OrderQueryService } from '@/order/application/query/order-query-service'
import { CancelOrderRequestBody } from '@/order/interface/dto/cancel-order-request-body'
import { CreateOrderRequestBody } from '@/order/interface/dto/create-order-request-body'
import { DeleteOrderRequestParam } from '@/order/interface/dto/delete-order-request-param'
import { GetOrderRequestParam } from '@/order/interface/dto/get-order-request-param'
import { GetOrderResponseBody } from '@/order/interface/dto/get-order-response-body'
import { GetOrdersRequestQuerystring } from '@/order/interface/dto/get-orders-request-querystring'
import { GetOrdersResponseBody } from '@/order/interface/dto/get-orders-response-body'
import { OrderErrorCode as ErrorCode } from '@/order/order-error-code'
import { OrderErrorMessage } from '@/order/order-error-message'

@Controller()
@ApiBearerAuth('token')
@ApiTags('Order')
@UseGuards(AuthGuard)
@UseInterceptors(LoggingInterceptor)
export class OrderController {
  private readonly logger = new Logger(OrderController.name)

  constructor(
    private readonly orderCommandService: OrderCommandService,
    private readonly orderQueryService: OrderQueryService
  ) {}

  @Get('/orders')
  @ApiOperation({ operationId: 'getOrders' })
  @ApiOkResponse({ type: GetOrdersResponseBody })
  public async getOrders(@Query() querystring: GetOrdersRequestQuerystring): Promise<GetOrdersResponseBody> {
    return this.orderQueryService.getOrders(querystring).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [])
    })
  }

  @Get('/orders/:orderId')
  @ApiOperation({ operationId: 'getOrder' })
  @ApiOkResponse({ type: GetOrderResponseBody })
  public async getOrder(@Param() param: GetOrderRequestParam): Promise<GetOrderResponseBody> {
    return this.orderQueryService.getOrder(param).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [OrderErrorMessage['주문을 찾을 수 없습니다.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND]
      ])
    })
  }

  @Post('/orders')
  @ApiOperation({ operationId: 'createOrder' })
  @ApiCreatedResponse()
  public async createOrder(@Body() body: CreateOrderRequestBody): Promise<void> {
    return this.orderCommandService.createOrder(new CreateOrderCommand(body)).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [])
    })
  }

  @Post('/orders/:orderId/cancel')
  @HttpCode(204)
  @ApiOperation({ operationId: 'cancelOrder' })
  @ApiNoContentResponse()
  public async cancelOrder(
    @Param('orderId') orderId: string,
    @Body() body: CancelOrderRequestBody
  ): Promise<void> {
    return this.orderCommandService.cancelOrder(new CancelOrderCommand({ ...body, orderId })).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [OrderErrorMessage['주문을 찾을 수 없습니다.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND],
        [OrderErrorMessage['이미 취소된 주문입니다.'], BadRequestException, ErrorCode.ORDER_ALREADY_CANCELLED],
        [OrderErrorMessage['결제 완료된 주문은 취소할 수 없습니다.'], BadRequestException, ErrorCode.ORDER_PAID_NOT_CANCELLABLE]
      ])
    })
  }

  @Delete('/orders/:orderId')
  @HttpCode(204)
  @ApiOperation({ operationId: 'deleteOrder' })
  @ApiNoContentResponse()
  public async deleteOrder(@Param() param: DeleteOrderRequestParam): Promise<void> {
    return this.orderCommandService.deleteOrder(param).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [OrderErrorMessage['주문을 찾을 수 없습니다.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND]
      ])
    })
  }
}
