import { ArgumentsHost, BadRequestException, HttpException, HttpStatus } from '@nestjs/common'

import { HttpExceptionFilter } from '@/common/http-exception.filter'

describe('HttpExceptionFilter', () => {
  const createHost = (): { host: ArgumentsHost; json: jest.Mock; status: jest.Mock } => {
    const json = jest.fn()
    const status = jest.fn().mockReturnValue({ json })
    const host = {
      switchToHttp: () => ({
        getResponse: () => ({ status }),
        getRequest: () => ({})
      })
    } as unknown as ArgumentsHost
    return { host, json, status }
  }

  it('HttpException_when_객체_응답_then_그대로_직렬화한다', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()
    const exception = new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message: ['msg'], error: 'Bad Request' })

    filter.catch(exception, host)

    expect(status).toHaveBeenCalledWith(400)
    expect(json).toHaveBeenCalledWith({ statusCode: 400, code: 'VALIDATION_FAILED', message: ['msg'], error: 'Bad Request' })
  })

  it('HttpException_when_문자열_응답_then_표준_형식으로_변환한다', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()
    const exception = new HttpException('간단한 메시지', HttpStatus.BAD_REQUEST)

    filter.catch(exception, host)

    expect(status).toHaveBeenCalledWith(400)
    expect(json).toHaveBeenCalledWith({ statusCode: 400, code: 'HTTP_EXCEPTION', message: '간단한 메시지', error: 'HttpException' })
  })

  it('non_HttpException_when_미처리_에러_then_500_표준_에러_응답으로_변환한다', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()

    filter.catch(new Error('예상치 못한 에러'), host)

    expect(status).toHaveBeenCalledWith(500)
    expect(json).toHaveBeenCalledWith({
      statusCode: 500,
      code: 'INTERNAL_ERROR',
      message: '예상치 못한 에러',
      error: 'Internal Server Error'
    })
  })

  it('non_Error_when_알수없는_예외_then_500_기본_메시지로_응답한다', () => {
    const filter = new HttpExceptionFilter()
    const { host, json, status } = createHost()

    filter.catch('문자열 throw', host)

    expect(status).toHaveBeenCalledWith(500)
    expect(json).toHaveBeenCalledWith({
      statusCode: 500,
      code: 'INTERNAL_ERROR',
      message: 'Internal server error',
      error: 'Internal Server Error'
    })
  })
})
