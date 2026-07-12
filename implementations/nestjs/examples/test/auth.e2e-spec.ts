import { BadRequestException, INestApplication, ValidationPipe } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { Test } from '@nestjs/testing'
import { TypeOrmModule } from '@nestjs/typeorm'
import { PostgreSqlContainer, StartedPostgreSqlContainer } from '@testcontainers/postgresql'
import request from 'supertest'

import { AuthModule } from '@/auth/auth-module'
import { CredentialEntity } from '@/auth/infrastructure/entity/credential.entity'
import { jwtConfig } from '@/config/jwt.config'

describe('AuthController (e2e)', () => {
  let container: StartedPostgreSqlContainer
  let app: INestApplication

  beforeAll(async () => {
    container = await new PostgreSqlContainer('postgres:16-alpine').start()

    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
        TypeOrmModule.forRoot({
          type: 'postgres',
          url: container.getConnectionUri(),
          entities: [CredentialEntity],
          synchronize: true
        }),
        AuthModule
      ]
    }).compile()

    app = moduleRef.createNestApplication()
    app.useGlobalPipes(new ValidationPipe({
      whitelist: true,
      transform: true,
      exceptionFactory: (errors) => {
        const message = errors.flatMap((error) => Object.values(error.constraints ?? {}))
        return new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })
      }
    }))
    await app.init()
  }, 120000)

  afterAll(async () => {
    await app?.close()
    await container?.stop()
  })

  it('sign-up_후_sign-in하면_201과_액세스_토큰을_반환한다', async () => {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: 'owner-1', password: 'password123!' }).expect(201)

    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: 'owner-1', password: 'password123!' })
      .expect(201)

    expect((response.body as { accessToken: string }).accessToken).toEqual(expect.any(String))
  })

  it('sign-in_when_비밀번호가_틀리면_then_401과_INVALID_CREDENTIALS를_반환한다', async () => {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: 'owner-2', password: 'password123!' }).expect(201)

    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: 'owner-2', password: 'wrong-password' })
      .expect(401)

    expect(response.body).toMatchObject({ code: 'INVALID_CREDENTIALS' })
  })

  it('sign-in_when_존재하지_않는_아이디면_then_401과_INVALID_CREDENTIALS를_반환한다', async () => {
    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: 'no-such-user', password: 'password123!' })
      .expect(401)

    expect(response.body).toMatchObject({ code: 'INVALID_CREDENTIALS' })
  })

  it('sign-up_when_이미_사용중인_아이디면_then_400과_USER_ID_ALREADY_EXISTS를_반환한다', async () => {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: 'owner-3', password: 'password123!' }).expect(201)

    const response = await request(app.getHttpServer())
      .post('/auth/sign-up')
      .send({ userId: 'owner-3', password: 'another-password' })
      .expect(400)

    expect(response.body).toMatchObject({ code: 'USER_ID_ALREADY_EXISTS' })
  })

  it('sign-up_when_비밀번호가_8자_미만이면_then_400과_VALIDATION_FAILED를_반환한다', async () => {
    const response = await request(app.getHttpServer())
      .post('/auth/sign-up')
      .send({ userId: 'owner-4', password: 'short' })
      .expect(400)

    expect(response.body).toMatchObject({ code: 'VALIDATION_FAILED' })
  })
})
