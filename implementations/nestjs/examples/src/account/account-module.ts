import { Module } from '@nestjs/common'
import { TypeOrmModule } from '@nestjs/typeorm'

import { AccountCommandService } from '@/account/application/command/account-command-service'
import { AccountQuery } from '@/account/application/query/account-query'
import { AccountQueryService } from '@/account/application/query/account-query-service'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { AccountQueryImpl } from '@/account/infrastructure/account-query-impl'
import { AccountRepositoryImpl } from '@/account/infrastructure/account-repository-impl'
import { AccountController } from '@/account/interface/account-controller'

@Module({
  imports: [TypeOrmModule.forFeature([AccountEntity, TransactionEntity])],
  controllers: [AccountController],
  providers: [
    AccountCommandService,
    AccountQueryService,
    { provide: AccountQuery, useClass: AccountQueryImpl },
    { provide: AccountRepository, useClass: AccountRepositoryImpl }
  ]
})
export class AccountModule {}
