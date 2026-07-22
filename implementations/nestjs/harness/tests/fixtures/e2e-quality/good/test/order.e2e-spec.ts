import { INestApplication } from '@nestjs/common'
import { Test } from '@nestjs/testing'
import * as nock from 'nock'
import * as request from 'supertest'

describe('OrderController (e2e)', () => {
  let app: INestApplication

  beforeAll(async () => {
    const module = await Test.createTestingModule({ imports: [] }).compile()
    app = module.createNestApplication()
    await app.init()
  })

  afterEach(() => nock.cleanAll())

  afterAll(() => app.close())

  it('GET /orders/:orderId — looks up an existing order', () => {
    return request(app.getHttpServer()).get('/orders/1').expect(200)
  })
})
