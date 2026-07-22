import { DataSource } from 'typeorm'

// true only when it's not production — evaluates to false in production, so it's safe.
export const DevDataSource = new DataSource({
  type: 'postgres',
  synchronize: process.env.NODE_ENV !== 'production'
})
