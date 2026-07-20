import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus } from '@nestjs/common'
import { Response } from 'express'

@Catch()
export class HttpExceptionFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp()
    const response = ctx.getResponse<Response>()

    if (exception instanceof HttpException) {
      const status = exception.getStatus()
      const exceptionResponse = exception.getResponse()
      response.status(status).json(
        typeof exceptionResponse === 'string'
          ? { statusCode: status, code: 'HTTP_EXCEPTION', message: exceptionResponse, error: exception.name }
          : exceptionResponse
      )
      return
    }

    const status = HttpStatus.INTERNAL_SERVER_ERROR
    const message = exception instanceof Error ? exception.message : 'Internal server error'

    response.status(status).json({
      statusCode: status,
      code: 'INTERNAL_ERROR',
      message,
      error: 'Internal Server Error'
    })
  }
}
