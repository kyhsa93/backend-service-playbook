import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AccountModule } from '@/account/account-module'
import { AuthModule } from '@/auth/auth-module'
import { SecretService } from '@/common/application/service/secret-service'
import { CorrelationIdMiddleware } from '@/common/correlation-id.middleware'
import { SecretServiceImpl } from '@/common/infrastructure/secret-service-impl'
import { jwtConfig } from '@/config/jwt.config'
import { AppDataSource } from '@/database/data-source'
import { OutboxModule } from '@/outbox/outbox-module'

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
    TypeOrmModule.forRoot({
      ...AppDataSource.options,
      autoLoadEntities: false,
      migrationsRun: true
    }),
    OutboxModule,
    AuthModule,
    AccountModule
  ],
  providers: [
    { provide: SecretService, useClass: SecretServiceImpl }
  ],
  exports: [SecretService]
})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(CorrelationIdMiddleware).forRoutes('*')
  }
}
