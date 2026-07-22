import { BadRequestException, Controller, NotFoundException, Param, Post } from '@nestjs/common'
import { ApiBadRequestResponse, ApiNoContentResponse, ApiNotFoundResponse, ApiOperation } from '@nestjs/swagger'
import { generateErrorResponse } from '@/common/generate-error-response'
import { ErrorResponseBody } from '@/common/interface/dto/error-response-body'
import { OrderErrorCode as ErrorCode } from '@/order/order-error-code'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

@Controller('orders')
export class OrderController {
  @Post(':orderId/cancel')
  @ApiOperation({ operationId: 'cancelOrder' })
  @ApiNoContentResponse({ description: 'The order was cancelled.' })
  @ApiBadRequestResponse({ description: 'The order is already cancelled (`ORDER_ALREADY_CANCELLED`).', type: ErrorResponseBody })
  @ApiNotFoundResponse({ description: 'No order exists with the given `orderId` (`ORDER_NOT_FOUND`).', type: ErrorResponseBody })
  public async cancel(@Param('orderId') orderId: string): Promise<void> {
    return Promise.resolve(orderId).then(() => undefined).catch((error: Error) => {
      throw generateErrorResponse(error.message, [
        [ErrorMessage['Order not found.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND],
        [ErrorMessage['The order is already cancelled.'], BadRequestException, ErrorCode.ORDER_ALREADY_CANCELLED]
      ])
    })
  }
}
