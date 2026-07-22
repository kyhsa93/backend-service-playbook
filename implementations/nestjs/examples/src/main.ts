import { BadRequestException, ValidationPipe } from '@nestjs/common'
import { NestFactory } from '@nestjs/core'
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger'

import { AppModule } from '@/app-module'
import { HttpExceptionFilter } from '@/common/http-exception.filter'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { getCorsOrigins, getPort, isProduction } from '@/config/app.config'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, {
    logger: isProduction()
      ? ['error', 'warn', 'log']
      : ['error', 'warn', 'log', 'debug', 'verbose']
  })

  // The global ValidationPipe — auto-applies class-validator, constructs a response with a code on failure
  app.useGlobalPipes(new ValidationPipe({
    whitelist: true,
    transform: true,
    exceptionFactory: (errors) => {
      const message = errors.flatMap((error) => Object.values(error.constraints ?? {}))
      return new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })
    }
  }))

  // The request-logging interceptor
  app.useGlobalInterceptors(new LoggingInterceptor())

  // The global exception filter — converts even unhandled exceptions that aren't an HttpException into the standard error response format
  app.useGlobalFilters(new HttpExceptionFilter())

  // CORS
  app.enableCors({
    origin: getCorsOrigins(),
    methods: ['GET', 'POST', 'PATCH', 'PUT', 'DELETE', 'OPTIONS'],
    credentials: true
  })

  // Swagger
  const swaggerConfig = new DocumentBuilder()
    .setTitle('Account Service API')
    .setDescription('API documentation for the DDD-based Account domain example service')
    .setVersion('0.1.0')
    .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }, 'token')
    .build()
  const swaggerDocument = SwaggerModule.createDocument(app, swaggerConfig)
  SwaggerModule.setup('docs', app, swaggerDocument)

  // Graceful Shutdown — runs Nest lifecycle hooks (onModuleDestroy, etc.) on receiving SIGTERM/SIGINT
  app.enableShutdownHooks()

  await app.listen(getPort())
}

bootstrap()
