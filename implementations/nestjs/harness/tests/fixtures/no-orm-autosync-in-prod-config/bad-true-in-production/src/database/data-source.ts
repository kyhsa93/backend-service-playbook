import { DataSource } from 'typeorm'

// A reversed mistake — it ends up true precisely when it's production.
export const AppDataSource = new DataSource({
  type: 'postgres',
  synchronize: process.env.NODE_ENV === 'production'
})
