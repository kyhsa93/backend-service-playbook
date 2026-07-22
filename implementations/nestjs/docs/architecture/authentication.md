# Authentication Pattern

### Authentication Flow

```
[Request]
1. Client: calls the API with an Authorization: Bearer <access_token> header
2. AuthGuard: extracts the token from the header → verifies via AuthService.verify()
3. AuthService: decodes the token → returns user information
4. AuthGuard: hands the user information to UserContextInterceptor (an internal handoff — never read directly by a Controller)
5. UserContextInterceptor: stores it in UserContextStore (AsyncLocalStorage) for the rest of the request → the Controller reads it via UserContextStore.getRequesterId()

[Sign-up]
1. Client → server: POST /auth/sign-up { userId, password }
2. SignUpCommandHandler: checks for a duplicate ID → hashes the password via PasswordHasher → saves the Credential
3. Server → client: 201

[Token Issuance]
1. Client → server: POST /auth/sign-in { userId, password }
2. SignInCommandHandler: looks up the stored hash via CredentialRepository → verifies the password via PasswordHasher.verify()
3. On successful verification, issues an Access Token via AuthService.sign()
4. Server → client: { accessToken }
```

**A nonexistent ID and a password mismatch respond with the same error message (`INVALID_CREDENTIALS`)** — responding differently for the two would let an attacker guess which IDs exist (user enumeration).

### Directory Structure

```
src/
  auth/
    auth-module.ts
    auth-service.ts                        ← issues/verifies tokens (JWT, a Technical Service)
    auth.guard.ts                          ← the Guard that extracts and verifies the Bearer token
    authenticated.decorator.ts             ← @Authenticated() — applies AuthGuard + UserContextInterceptor together
    public.decorator.ts                    ← @Public() — marks a route as not requiring authentication
    domain/
      credential.ts                        ← the Credential Aggregate (credentialId, userId, passwordHash)
      credential-repository.ts             ← abstract class
      credential-find-query.ts
    application/
      command/
        sign-up-command.ts / -handler.ts   ← checks for a duplicate ID → hashes → saves
        sign-in-command.ts / -handler.ts   ← looks up the hash → verifies → issues the token
      service/
        password-hasher.ts                 ← abstract class (a Technical Service)
    infrastructure/
      bcrypt-password-hasher.ts            ← the PasswordHasher implementation (bcryptjs)
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

Password hashing follows the same Technical Service pattern as email sending (`NotificationService`) — an ABC in `application/service/`, an implementation in `infrastructure/`, so the Domain/Application doesn't depend on a concrete library like `bcryptjs`.

### AuthGuard — Extracting and Verifying the Bearer Token

Applied (always together with `UserContextInterceptor`, via the `@Authenticated()` decorator — see below) on every Controller that requires authentication. Extracts the `Bearer` token from the `Authorization` header and verifies it via `AuthService.verify()`.

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

    // A Guard's canActivate() only returns true/false — it has no callback of its own to wrap
    // the rest of the pipeline, so it can't open UserContextStore's AsyncLocalStorage scope
    // itself. This field is an internal-only handoff to UserContextInterceptor (always applied
    // together, via @Authenticated()) — a Controller must never read it directly.
    request.__verifiedUser = user
    return true
  }
}
```

### UserContextStore / UserContextInterceptor — Request-Scoped Storage for the Authenticated User

A Controller reads the authenticated user's info via `UserContextStore`, never via `@Req()`/the request object — the same principle `CorrelationIdStore` already applies for the Correlation ID (see [observability.md](observability.md)). `UserContextStore` itself is a thin `AsyncLocalStorage` wrapper:

```typescript
// src/common/user-context-store.ts
import { AsyncLocalStorage } from 'async_hooks'

export interface UserContext {
  userId: string
}

const storage = new AsyncLocalStorage<UserContext>()

export const UserContextStore = {
  run: (user: UserContext, fn: () => void) => storage.run(user, fn),
  getUser: (): UserContext | undefined => storage.getStore(),
  getRequesterId: (): string => {
    const user = storage.getStore()
    if (!user) throw new Error('UserContextStore.getRequesterId() called outside an authenticated request context.')
    return user.userId
  }
}
```

Unlike Correlation ID injection, which a single piece of Middleware can both populate *and* wrap (`correlationIdStorage.run(id, () => next())`), authentication needs two cooperating stages: `AuthGuard` verifies the token but — as a Guard — has no "wrap the rest of the pipeline" callback to open the storage scope with. `UserContextInterceptor` is the piece that does have one (`next.handle()`), so it picks up what the Guard verified and opens the scope around everything downstream of it:

```typescript
// src/common/user-context.interceptor.ts
import { CallHandler, ExecutionContext, Injectable, NestInterceptor } from '@nestjs/common'
import { Observable } from 'rxjs'

import { UserContextStore } from '@/common/user-context-store'

@Injectable()
export class UserContextInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const request = context.switchToHttp().getRequest()
    const user = request.__verifiedUser

    if (!user) return next.handle()

    return new Observable((subscriber) => {
      UserContextStore.run(user, () => {
        next.handle().subscribe(subscriber)
      })
    })
  }
}
```

### @Authenticated() — Applying Both Together

`AuthGuard` and `UserContextInterceptor` must never be applied separately (the interceptor depends on the guard having already run and set `request.__verifiedUser`), so a composite decorator applies both in one call — this also keeps every protected Controller's class-level decorator list exactly as short as it was before this pattern existed.

```typescript
// src/auth/authenticated.decorator.ts
import { applyDecorators, UseGuards, UseInterceptors } from '@nestjs/common'

import { AuthGuard } from '@/auth/auth.guard'
import { UserContextInterceptor } from '@/common/user-context.interceptor'

export const Authenticated = (): ReturnType<typeof applyDecorators> => applyDecorators(
  UseGuards(AuthGuard),
  UseInterceptors(UserContextInterceptor)
)
```

### AuthService — Issuing and Verifying Tokens

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

### Credential — Password Verification

```typescript
// src/auth/domain/credential.ts
export class Credential {
  public readonly credentialId: string
  public readonly userId: string
  public readonly passwordHash: string  // the plaintext password is never kept anywhere in domain/application
  public readonly createdAt: Date
  // ...
  public static create(params: { userId: string; passwordHash: string }): Credential {
    return new Credential(params)
  }
}

// src/auth/application/service/password-hasher.ts — the Technical Service ABC
export abstract class PasswordHasher {
  public abstract hash(plainPassword: string): Promise<string>
  public abstract verify(plainPassword: string, passwordHash: string): Promise<boolean>
}

// src/auth/infrastructure/bcrypt-password-hasher.ts — the implementation
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

### SignInCommandHandler — Lookup → Verify → Issue the Token

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
    // respond with the same message for a nonexistent ID and a password mismatch — prevents user enumeration
    if (!credential) throw new Error(ErrorMessage['Incorrect username or password.'])

    const isValid = await this.passwordHasher.verify(command.password, credential.passwordHash)
    if (!isValid) throw new Error(ErrorMessage['Incorrect username or password.'])

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

### Usage in a Controller

```typescript
// a Controller requiring authentication — @Authenticated() applied at the class level
@Controller()
@ApiBearerAuth('token')
@ApiTags('Order')
@Authenticated()
export class OrderController {
  // access the authenticated user's info via UserContextStore, never @Req()
  @Get('/orders')
  public async getOrders(): Promise<GetOrdersResponseBody> {
    const requesterId = UserContextStore.getRequesterId()
    ...
  }
}
```

```typescript
// a Controller that doesn't require authentication — mark the intent explicitly with @Public() (the harness's auth rule checks for this)
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

`@Public()` only marks the route via `SetMetadata`, and `AuthGuard` reads it via `Reflector` to skip token verification — `AuthController` already works without `@Public()` since `@Authenticated()` was never applied to it in the first place, but marking it explicitly means it won't accidentally get blocked later if authentication switches to a global Guard (`APP_GUARD`). Public endpoints like `GET /health/*` are likewise annotated with `@Public()`.

### Swagger Authentication Configuration

```typescript
// main.ts
const document = SwaggerModule.createDocument(app, 
  new DocumentBuilder()
    .setTitle('API')
    .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }, 'token')
    .build()
)
```

- The second argument to `addBearerAuth`, `'token'`, is matched with the Controller's `@ApiBearerAuth('token')`.
- Entering a token via the Authorize button in Swagger UI automatically includes an `Authorization: Bearer <token>` header on every request.

### Interceptor — Logging/Transformation

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
