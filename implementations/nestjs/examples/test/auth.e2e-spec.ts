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

  it('sign-in_after_sign-up_returns_201_and_an_access_token', async () => {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: 'owner-1', password: 'password123!' }).expect(201)

    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: 'owner-1', password: 'password123!' })
      .expect(201)

    expect((response.body as { accessToken: string }).accessToken).toEqual(expect.any(String))
  })

  it('sign-in_when_the_password_is_wrong_then_returns_401_and_INVALID_CREDENTIALS', async () => {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: 'owner-2', password: 'password123!' }).expect(201)

    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: 'owner-2', password: 'wrong-password' })
      .expect(401)

    expect(response.body).toMatchObject({ code: 'INVALID_CREDENTIALS' })
  })

  it('sign-in_when_the_username_does_not_exist_then_returns_401_and_INVALID_CREDENTIALS', async () => {
    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: 'no-such-user', password: 'password123!' })
      .expect(401)

    expect(response.body).toMatchObject({ code: 'INVALID_CREDENTIALS' })
  })

  it('sign-up_when_the_username_is_already_in_use_then_returns_400_and_USER_ID_ALREADY_EXISTS', async () => {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: 'owner-3', password: 'password123!' }).expect(201)

    const response = await request(app.getHttpServer())
      .post('/auth/sign-up')
      .send({ userId: 'owner-3', password: 'another-password' })
      .expect(400)

    expect(response.body).toMatchObject({ code: 'USER_ID_ALREADY_EXISTS' })
  })

  it('sign-up_when_the_password_is_shorter_than_8_characters_then_returns_400_and_VALIDATION_FAILED', async () => {
    const response = await request(app.getHttpServer())
      .post('/auth/sign-up')
      .send({ userId: 'owner-4', password: 'short' })
      .expect(400)

    expect(response.body).toMatchObject({ code: 'VALIDATION_FAILED' })
  })
})
