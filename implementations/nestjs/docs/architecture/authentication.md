# 인증 패턴

### 인증 흐름

```
[요청]
1. 클라이언트: Authorization: Bearer <access_token> 헤더를 포함하여 API 호출
2. AuthGuard: 헤더에서 토큰 추출 → AuthService.verify()로 검증
3. AuthService: 토큰 디코딩 → 사용자 정보 반환
4. AuthGuard: request.user에 사용자 정보 할당 → Controller로 전달

[가입]
1. 클라이언트 → 서버: POST /auth/sign-up { userId, password }
2. SignUpCommandHandler: 아이디 중복 확인 → PasswordHasher로 비밀번호 해싱 → Credential 저장
3. 서버 → 클라이언트: 201

[토큰 발급]
1. 클라이언트 → 서버: POST /auth/sign-in { userId, password }
2. SignInCommandHandler: CredentialRepository로 저장된 해시 조회 → PasswordHasher.verify()로 비밀번호 검증
3. 검증 성공 시 AuthService.sign()으로 Access Token 발급
4. 서버 → 클라이언트: { accessToken }
```

**아이디 미존재와 비밀번호 불일치는 동일한 에러 메시지(`INVALID_CREDENTIALS`)로 응답한다** — 둘을 구분해서 응답하면 공격자가 존재하는 아이디를 추측할 수 있다(user enumeration).

### 디렉토리 구조

```
src/
  auth/
    auth-module.ts
    auth-service.ts                        ← 토큰 발급/검증 (JWT, Technical Service)
    auth.guard.ts                          ← Bearer 토큰 추출 및 검증 Guard
    public.decorator.ts                    ← @Public() — 인증 불필요 라우트 명시
    domain/
      credential.ts                        ← Credential Aggregate (credentialId, userId, passwordHash)
      credential-repository.ts             ← abstract class
      credential-find-query.ts
    application/
      command/
        sign-up-command.ts / -handler.ts   ← 아이디 중복 확인 → 해싱 → 저장
        sign-in-command.ts / -handler.ts   ← 해시 조회 → 검증 → 토큰 발급
      service/
        password-hasher.ts                 ← abstract class (Technical Service)
    infrastructure/
      bcrypt-password-hasher.ts            ← PasswordHasher 구현체 (bcryptjs)
      credential-repository-impl.ts
      entity/
        credential.entity.ts
    interface/
      auth-controller.ts                   ← POST /auth/sign-up, POST /auth/sign-in
      dto/
        sign-up-request-body.ts
        sign-in-request-body.ts
        sign-in-response-body.ts
```

비밀번호 해싱은 이메일 발송(`NotificationService`)과 동일한 Technical Service 패턴이다 — `application/service/`에 ABC, `infrastructure/`에 구현체를 두어 Domain/Application이 `bcryptjs` 같은 구체 라이브러리에 의존하지 않게 한다.

### AuthGuard — Bearer 토큰 추출 및 검증

모든 인증 필요 Controller에 클래스 레벨로 적용한다. `Authorization` 헤더에서 `Bearer` 토큰을 추출하고, `AuthService.verify()`로 검증한 뒤 `request.user`에 사용자 정보를 할당한다.

```typescript
// src/auth/auth.guard.ts
import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common'

import { AuthService } from '@/auth/auth-service'

@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private readonly authService: AuthService) {}

  public async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest()
    const authorization = request.headers.authorization
    if (!authorization?.startsWith('Bearer ')) throw new UnauthorizedException()

    const token = authorization.replace('Bearer ', '')
    const user = await this.authService.verify(token)
    if (!user) throw new UnauthorizedException()

    request.user = user
    return true
  }
}
```

### AuthService — 토큰 발급 및 검증

```typescript
// src/auth/auth-service.ts
import { Injectable } from '@nestjs/common'
import { ConfigService } from '@nestjs/config'
import { JwtService, JwtSignOptions } from '@nestjs/jwt'

@Injectable()
export class AuthService {
  constructor(
    private readonly jwtService: JwtService,
    private readonly configService: ConfigService
  ) {}

  public async sign(payload: { userId: string }): Promise<string> {
    return this.jwtService.signAsync(payload, {
      secret: this.configService.get<string>('jwt.secret'),
      expiresIn: this.configService.get<string>('jwt.expiresIn')
    } as unknown as JwtSignOptions)
  }

  public async verify(token: string): Promise<{ userId: string } | null> {
    try {
      return await this.jwtService.verifyAsync(token, {
        secret: this.configService.get<string>('jwt.secret')
      })
    } catch {
      return null
    }
  }
}
```

### Credential — 비밀번호 검증

```typescript
// src/auth/domain/credential.ts
export class Credential {
  public readonly credentialId: string
  public readonly userId: string
  public readonly passwordHash: string  // 평문 비밀번호는 domain/application 어디에도 보관하지 않는다
  public readonly createdAt: Date
  // ...
  public static create(params: { userId: string; passwordHash: string }): Credential {
    return new Credential(params)
  }
}

// src/auth/application/service/password-hasher.ts — Technical Service ABC
export abstract class PasswordHasher {
  public abstract hash(plainPassword: string): Promise<string>
  public abstract verify(plainPassword: string, passwordHash: string): Promise<boolean>
}

// src/auth/infrastructure/bcrypt-password-hasher.ts — 구현체
import { compare, hash } from 'bcryptjs'

@Injectable()
export class BcryptPasswordHasher implements PasswordHasher {
  public async hash(plainPassword: string): Promise<string> {
    return hash(plainPassword, 12)
  }
  public async verify(plainPassword: string, passwordHash: string): Promise<boolean> {
    return compare(plainPassword, passwordHash)
  }
}
```

### SignInCommandHandler — 조회 → 검증 → 토큰 발급

```typescript
// src/auth/application/command/sign-in-command-handler.ts
@CommandHandler(SignInCommand)
export class SignInCommandHandler implements ICommandHandler<SignInCommand, string> {
  constructor(
    private readonly credentialRepository: CredentialRepository,
    private readonly passwordHasher: PasswordHasher,
    private readonly authService: AuthService
  ) {}

  public async execute(command: SignInCommand): Promise<string> {
    const credential = await this.credentialRepository
      .findCredentials({ userId: command.userId, take: 1, page: 0 })
      .then((r) => r.credentials.pop())
    // 아이디 미존재와 비밀번호 불일치를 동일한 메시지로 응답 — user enumeration 방지
    if (!credential) throw new Error(ErrorMessage['아이디 또는 비밀번호가 올바르지 않습니다.'])

    const isValid = await this.passwordHasher.verify(command.password, credential.passwordHash)
    if (!isValid) throw new Error(ErrorMessage['아이디 또는 비밀번호가 올바르지 않습니다.'])

    return this.authService.sign({ userId: credential.userId })
  }
}
```

### AuthModule

```typescript
// src/auth/auth-module.ts
import { Module } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'
import { JwtModule } from '@nestjs/jwt'
import { TypeOrmModule } from '@nestjs/typeorm'

@Module({
  imports: [CqrsModule, JwtModule.register({}), TypeOrmModule.forFeature([CredentialEntity])],
  controllers: [AuthController],
  providers: [
    AuthService, AuthGuard,
    SignUpCommandHandler, SignInCommandHandler,
    { provide: CredentialRepository, useClass: CredentialRepositoryImpl },
    { provide: PasswordHasher, useClass: BcryptPasswordHasher }
  ],
  exports: [AuthService, AuthGuard]
})
export class AuthModule {}
```

### Controller에서 사용

```typescript
// 인증 필요 Controller — 클래스 레벨에 AuthGuard 적용
@Controller()
@ApiBearerAuth('token')
@ApiTags('Order')
@UseGuards(AuthGuard)
export class OrderController {
  // request.user로 인증된 사용자 정보 접근
  @Get('/orders')
  public async getOrders(
    @Req() req: Request & { user: { userId: string } }
  ): Promise<GetOrdersResponseBody> { ... }
}
```

```typescript
// 인증 불필요 Controller — @Public()으로 의도를 명시 (harness의 auth 규칙이 이를 검사한다)
@Controller()
@ApiTags('Auth')
export class AuthController {
  constructor(private readonly commandBus: CommandBus) {}

  @Public()
  @Post('/auth/sign-up')
  @HttpCode(201)
  public async signUp(@Body() body: SignUpRequestBody): Promise<void> {
    return this.commandBus.execute(new SignUpCommand(body))
  }

  @Public()
  @Post('/auth/sign-in')
  @ApiCreatedResponse({ type: SignInResponseBody })
  public async signIn(@Body() body: SignInRequestBody): Promise<SignInResponseBody> {
    const accessToken = await this.commandBus.execute(new SignInCommand(body))
    return { accessToken }
  }
}
```

`@Public()`은 `SetMetadata`로 라우트에 마킹만 하고, `AuthGuard`가 `Reflector`로 이를 읽어 토큰 검증을 건너뛴다 — `AuthController`는 애초에 `@UseGuards(AuthGuard)`가 적용되지 않아 `@Public()` 없이도 동작하지만, 명시적으로 표시해두면 나중에 인증이 전역 Guard(`APP_GUARD`)로 바뀌어도 실수로 막히지 않는다. `GET /health/*` 같은 공개 엔드포인트도 동일하게 `@Public()`을 붙인다.

### Swagger 인증 설정

```typescript
// main.ts
const document = SwaggerModule.createDocument(app, 
  new DocumentBuilder()
    .setTitle('API')
    .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }, 'token')
    .build()
)
```

- `addBearerAuth`의 두 번째 인자 `'token'`은 Controller의 `@ApiBearerAuth('token')`과 일치시킨다.
- Swagger UI에서 Authorize 버튼으로 토큰을 입력하면 모든 요청에 `Authorization: Bearer <token>` 헤더가 자동 포함된다.

### Interceptor — 로깅/변환

```typescript
// src/common/logging.interceptor.ts
@Injectable()
export class LoggingInterceptor implements NestInterceptor {
  private readonly logger = new Logger('HTTP')

  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const req = context.switchToHttp().getRequest()
    const { method, url } = req
    const now = Date.now()

    return next.handle().pipe(
      tap(() => this.logger.log(`${method} ${url} — ${Date.now() - now}ms`))
    )
  }
}
```
