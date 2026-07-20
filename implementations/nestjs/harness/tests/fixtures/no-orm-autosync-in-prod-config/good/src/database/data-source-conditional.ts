import { DataSource } from 'typeorm'

// production이 아닐 때만 true — production일 때는 false로 평가되므로 안전하다.
export const DevDataSource = new DataSource({
  type: 'postgres',
  synchronize: process.env.NODE_ENV !== 'production'
})
