import { Module } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { TypeOrmModule } from '@nestjs/typeorm'

import { CloseAccountCommandHandler } from '@/account/application/command/close-account-command-handler'
import { CreateAccountCommandHandler } from '@/account/application/command/create-account-command-handler'
import { DepositCommandHandler } from '@/account/application/command/deposit-command-handler'
import { ReactivateAccountCommandHandler } from '@/account/application/command/reactivate-account-command-handler'
import { SuspendAccountCommandHandler } from '@/account/application/command/suspend-account-command-handler'
import { WithdrawCommandHandler } from '@/account/application/command/withdraw-command-handler'
import { AccountClosedHandler } from '@/account/application/event/account-closed-handler'
import { AccountCreatedHandler } from '@/account/application/event/account-created-handler'
import { AccountReactivatedHandler } from '@/account/application/event/account-reactivated-handler'
import { AccountSuspendedHandler } from '@/account/application/event/account-suspended-handler'
import { MoneyDepositedHandler } from '@/account/application/event/money-deposited-handler'
import { MoneyWithdrawnHandler } from '@/account/application/event/money-withdrawn-handler'
import { AccountQuery } from '@/account/application/query/account-query'
import { GetAccountQueryHandler } from '@/account/application/query/get-account-query-handler'
import { GetTransactionsQueryHandler } from '@/account/application/query/get-transactions-query-handler'
import { AccountRepository } from '@/account/domain/account-repository'
import { AccountEntity } from '@/account/infrastructure/entity/account.entity'
import { TransactionEntity } from '@/account/infrastructure/entity/transaction.entity'
import { AccountQueryImpl } from '@/account/infrastructure/account-query-impl'
import { AccountRepositoryImpl } from '@/account/infrastructure/account-repository-impl'
import { AccountController } from '@/account/interface/account-controller'

@Module({
  imports: [CqrsModule, TypeOrmModule.forFeature([AccountEntity, TransactionEntity])],
  controllers: [AccountController],
  providers: [
    // Command Handlers
    CreateAccountCommandHandler,
    DepositCommandHandler,
    WithdrawCommandHandler,
    SuspendAccountCommandHandler,
    ReactivateAccountCommandHandler,
    CloseAccountCommandHandler,
    // Query Handlers
    GetAccountQueryHandler,
    GetTransactionsQueryHandler,
    // Event Handlers
    AccountCreatedHandler,
    MoneyDepositedHandler,
    MoneyWithdrawnHandler,
    AccountSuspendedHandler,
    AccountReactivatedHandler,
    AccountClosedHandler,
    // Repositories
    { provide: AccountRepository, useClass: AccountRepositoryImpl },
    // Query 구현체
    { provide: AccountQuery, useClass: AccountQueryImpl }
  ]
})
export class AccountModule {}
