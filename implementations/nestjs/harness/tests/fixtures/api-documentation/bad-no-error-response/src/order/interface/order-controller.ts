import { BadRequestException, Controller, NotFoundException, Param, Post } from '@nestjs/common'
import { ApiNoContentResponse, ApiOperation } from '@nestjs/swagger'
import { generateErrorResponse } from '@/common/generate-error-response'
import { OrderErrorCode as ErrorCode } from '@/order/order-error-code'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

@Controller('orders')
export class OrderController {
  @Post(':orderId/cancel')
  @ApiOperation({
    operationId: 'cancelOrder',
    summary: 'Cancel an order',
    description: 'Cancels an order that has not yet been paid.'
  })
  @ApiNoContentResponse({ description: 'The order was cancelled.' })
  public async cancel(@Param('orderId') orderId: string): Promise<void> {
    return Promise.resolve(orderId).then(() => undefined).catch((error: Error) => {
      throw generateErrorResponse(error.message, [
        [ErrorMessage['Order not found.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND],
        [ErrorMessage['The order is already cancelled.'], BadRequestException, ErrorCode.ORDER_ALREADY_CANCELLED]
      ])
    })
  }
}
