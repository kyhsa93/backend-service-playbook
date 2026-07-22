import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus } from '@nestjs/common'
import { Response } from 'express'

@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp()
    const response = ctx.getResponse<Response>()

    const status = HttpStatus.INTERNAL_SERVER_ERROR
    const message = exception instanceof Error ? exception.message : 'Internal server error'

    // a stack field is added and the error field is missing — violates the 4-field (statusCode/code/message/error) rule
    response.status(status).json({
      statusCode: status,
      code: 'INTERNAL_ERROR',
      message,
      stack: exception instanceof Error ? exception.stack : undefined
    })
  }
}
