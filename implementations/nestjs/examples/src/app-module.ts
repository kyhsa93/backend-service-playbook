import { Module } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AccountModule } from '@/account/account-module'
import { AuthModule } from '@/auth/auth-module'
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
  ]
})
export class AppModule {}
