// Must be the very first import in this file — see src/tracing.ts for why (it patches Node's
// http module in place, and only requests made after it runs get instrumented).
import '@/tracing'

import { BadRequestException, ValidationPipe } from '@nestjs/common'
import { NestFactory } from '@nestjs/core'
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger'
import { NextFunction, Request, Response } from 'express'
import helmet from 'helmet'

import { AppModule } from '@/app-module'
import { HttpExceptionFilter } from '@/common/http-exception.filter'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { MetricsInterceptor } from '@/common/metrics.interceptor'
import { getCorsOrigins, getPort, isProduction } from '@/config/app.config'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, {
    logger: isProduction()
      ? ['error', 'warn', 'log']
      : ['error', 'warn', 'log', 'debug', 'verbose']
  })

  // Security headers (X-Content-Type-Options, X-Frame-Options, HSTS, etc), applied as early as
  // possible in the pipeline. helmet's default Content-Security-Policy blocks Swagger UI's
  // inline scripts/styles, so /docs gets its own helmet instance with CSP turned off (keeping
  // every other header) rather than relaxing CSP for the whole app.
  const defaultHelmet = helmet()
  const docsHelmet = helmet({ contentSecurityPolicy: false })
  app.use((req: Request, res: Response, next: NextFunction) => {
    const isSwaggerPath = req.path === '/docs' || req.path.startsWith('/docs/') || req.path === '/docs-json'
    const middleware = isSwaggerPath ? docsHelmet : defaultHelmet
    middleware(req, res, next)
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

  // The request-logging interceptor, plus the Prometheus HTTP-metrics interceptor alongside it
  // — both read the same (method, route, status, duration) facts off the request/response, for
  // two different consumers (structured logs vs. a /metrics scrape).
  app.useGlobalInterceptors(new LoggingInterceptor(), new MetricsInterceptor())

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
