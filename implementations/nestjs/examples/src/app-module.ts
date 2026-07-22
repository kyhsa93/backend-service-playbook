import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common'
import { APP_GUARD } from '@nestjs/core'
import { ConfigModule } from '@nestjs/config'
import { ScheduleModule } from '@nestjs/schedule'
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AccountModule } from '@/account/account-module'
import { AuthModule } from '@/auth/auth-module'
import { CardModule } from '@/card/card-module'
import { SecretService } from '@/common/application/service/secret-service'
import { CorrelationIdMiddleware } from '@/common/correlation-id.middleware'
import { HealthController } from '@/common/interface/health-controller'
import { SecretServiceImpl } from '@/common/infrastructure/secret-service-impl'
import { ShutdownState } from '@/common/infrastructure/shutdown-state'
import { validateConfig } from '@/config/validation.config'
import { jwtConfig } from '@/config/jwt.config'
import { getThrottlerConfig } from '@/config/throttle.config'
import { AppDataSource } from '@/database/data-source'
import { OutboxModule } from '@/outbox/outbox-module'
import { PaymentModule } from '@/payment/payment-module'
import { TaskQueueModule } from '@/task-queue/task-queue-module'

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig], validate: validateConfig }),
    ScheduleModule.forRoot(), // enables scheduling decorators such as OutboxPoller's @Interval
    ThrottlerModule.forRoot(getThrottlerConfig()), // defaults: short 3/second / medium 20/10 seconds / long 100/minute — override via THROTTLE_* environment variables
    TypeOrmModule.forRoot({
      ...AppDataSource.options,
      autoLoadEntities: false,
      migrationsRun: true
    }),
    OutboxModule,
    TaskQueueModule,
    AuthModule,
    AccountModule,
    CardModule,
    PaymentModule
  ],
  controllers: [HealthController],
  providers: [
    { provide: SecretService, useClass: SecretServiceImpl },
    { provide: APP_GUARD, useClass: ThrottlerGuard },
    ShutdownState
  ],
  exports: [SecretService]
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(CorrelationIdMiddleware).forRoutes('{*splat}')
  }
}
