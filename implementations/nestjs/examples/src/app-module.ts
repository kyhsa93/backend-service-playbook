import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common'
import { APP_GUARD } from '@nestjs/core'
import { ConfigModule } from '@nestjs/config'
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AccountModule } from '@/account/account-module'
import { AuthModule } from '@/auth/auth-module'
import { SecretService } from '@/common/application/service/secret-service'
import { CorrelationIdMiddleware } from '@/common/correlation-id.middleware'
import { HealthController } from '@/common/interface/health-controller'
import { SecretServiceImpl } from '@/common/infrastructure/secret-service-impl'
import { ShutdownState } from '@/common/infrastructure/shutdown-state'
import { validateConfig } from '@/config/validation.config'
import { jwtConfig } from '@/config/jwt.config'
import { AppDataSource } from '@/database/data-source'
import { OutboxModule } from '@/outbox/outbox-module'

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig], validate: validateConfig }),
    ThrottlerModule.forRoot({
      throttlers: [
        { name: 'short', ttl: 1000, limit: 3 }, // 1초에 3회
        { name: 'medium', ttl: 10000, limit: 20 }, // 10초에 20회
        { name: 'long', ttl: 60000, limit: 100 } // 1분에 100회
      ]
    }),
    TypeOrmModule.forRoot({
      ...AppDataSource.options,
      autoLoadEntities: false,
      migrationsRun: true
    }),
    OutboxModule,
    AuthModule,
    AccountModule
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
