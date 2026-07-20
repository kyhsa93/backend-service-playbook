import { DataSource } from 'typeorm'

// 반대로 쓴 실수 — production일 때 오히려 true가 되어 버린다.
export const AppDataSource = new DataSource({
  type: 'postgres',
  synchronize: process.env.NODE_ENV === 'production'
})
