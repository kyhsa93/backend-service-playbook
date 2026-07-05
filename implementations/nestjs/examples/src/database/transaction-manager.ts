import { Injectable } from '@nestjs/common'
import { InjectDataSource } from '@nestjs/typeorm'
import { AsyncLocalStorage } from 'node:async_hooks'
import { DataSource, EntityManager } from 'typeorm'

@Injectable()
export class TransactionManager {
  private readonly als = new AsyncLocalStorage<EntityManager>()

  constructor(@InjectDataSource() private readonly dataSource: DataSource) {}

  public async run<T>(fn: () => Promise<T>): Promise<T> {
    const existing = this.als.getStore()
    if (existing) return fn()

    return this.dataSource.transaction((manager) => (
      new Promise<T>((resolve, reject) => {
        this.als.run(manager, () => {
          fn().then(resolve, reject)
        })
      })
    ))
  }

  public getManager(): EntityManager {
    return this.als.getStore() ?? this.dataSource.manager
  }
}
