import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common'
import { APP_GUARD } from '@nestjs/core'
import { ConfigModule } from '@nestjs/config'
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

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig], validate: validateConfig }),
    ThrottlerModule.forRoot(getThrottlerConfig()), // 기본값: short 1초 3회 / medium 10초 20회 / long 1분 100회 — THROTTLE_* 환경 변수로 override
    TypeOrmModule.forRoot({
      ...AppDataSource.options,
      autoLoadEntities: false,
      migrationsRun: true
    }),
    OutboxModule,
    AuthModule,
    AccountModule,
    CardModule
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
    consumer.apply(CorrelationIdMiddleware).forRoutes('*')
  }
}
