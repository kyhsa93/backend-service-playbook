# Module Composition Pattern — `Depends` Instead of a DI Container

FastAPI has no equivalent of NestJS's `@Module`/DI container. This document explains what FastAPI uses in its place, in three parts — ① how the package tree stands in for "module boundaries", ② how `Depends` factory functions stand in for "bindings", and ③ how `APIRouter` stands in for "controller registration".

## ① Python package tree = NestJS's "module"

NestJS uses the `@Module({ providers, controllers, exports })` decorator to explicitly declare the boundary of a Bounded Context. FastAPI has no such declaration — instead, **the package directory itself is the boundary**.

```
src/
  account/                    ← corresponds to "AccountModule" — no separate declaration, the directory itself is the boundary
    domain/
    application/
    infrastructure/
    interface/
  database.py                 ← shared infrastructure (see shared-modules.md)
```

- The principle **1 Bounded Context = 1 top-level package** (`src/account/`) is the same as in NestJS — the only difference is "whether there is syntax to declare this boundary".
- There is no mechanism corresponding to NestJS's `exports: [UserService]` (choosing which providers to expose to other modules) — Python can import anything via `from src.account.application... import X`. The boundary is upheld by **convention**: never import directly into another package's `domain/` or `infrastructure/`; only use the Handlers/Adapters of the `application/` layer as the external entry point (see [cross-domain.md](cross-domain.md)).

## ② `Depends` factory function = NestJS's `{ provide, useClass }`

NestJS declaratively binds an interface (abstract class) to its implementation in a module's `providers` array. FastAPI replaces this binding with **writing a factory function** — a pattern this repository already uses.

```python
# src/account/interface/rest/account_router.py — actual code
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)
```

| NestJS | FastAPI |
|--------|---------|
| Declare `{ provide: AccountRepository, useClass: SqlAlchemyAccountRepository }` in the module's `providers` | Define a `_repo()` factory function and reference it as `Depends(_repo)` in the route signature |
| The container inspects constructor arguments and automatically builds the dependency graph (`constructor(private readonly repo: AccountRepository)`) | FastAPI recursively resolves the factory function's arguments — if `_repo` requires `Depends(get_session)`, `get_session()` is resolved first |
| Bindings are scattered across **module declaration files** | The binding **is** the factory function definition itself — to find "where is this bound", just grep for the factory function |
| Scope (`REQUEST`, `SINGLETON`) is specified via decorator | `Depends` is **request-scoped** by default (cached within the same request for the same parameter combination). A singleton is created via a module-level global variable (`engine`, `SessionLocal` already follow this pattern) |

The route function parameter's type hint is declared as the ABC (`NotificationService`), but the actual injected value is the implementation the factory returns (`SesNotificationService`) — see the "Infrastructure layer" section of [layer-architecture.md](layer-architecture.md).

## ③ `APIRouter` composition = NestJS's `controllers` registration

```python
# src/account/interface/rest/account_router.py
router = APIRouter(prefix="/accounts", tags=["Account"])

@router.post("", status_code=201, ...)
async def create_account(...): ...
```

```python
# main.py — compose routers at the top level of the app
app.include_router(account_router)
```

Adding a second domain is as simple as adding one line, `app.include_router(user_router, prefix="/users")` — conceptually the same point as NestJS's `AppModule` composing domain modules via `imports: [AccountModule, UserModule]`, except FastAPI has no dedicated class for this composition (`AppModule`) — `main.py` itself doubles as that role. Details: [bootstrap.md](bootstrap.md).

## Python's circular imports — arise differently from NestJS, and are resolved differently

In NestJS, a circular dependency arises when module A `imports` module B and B in turn `imports` A, requiring either a workaround with `forwardRef()` or a redesign. Python has no decorator-based module graph; instead, a circular import arises **when the top-level `import` statements of two modules point at each other** — this is a language-level issue, not a framework issue.

```python
# src/account/application/adapter/user_adapter.py
from src.user.domain.user import User  # ← imports the user package at the top level

# src/user/application/adapter/account_adapter.py
from src.account.domain.account import Account  # ← imports the account package at the top level
# if both files import each other, this raises: ImportError: cannot import name ... (partially initialized module)
```

**Resolution priority:**

1. **First, re-examine the boundary** — if two domains need to reference each other directly, the Bounded Context split is likely wrong. First check whether the Adapter pattern in [cross-domain.md](cross-domain.md) can eliminate the direct dependency in one direction.
2. **If only a type hint is needed, defer it with `TYPE_CHECKING`** — since it isn't imported at runtime, no cycle occurs.

   ```python
   from typing import TYPE_CHECKING

   if TYPE_CHECKING:
       from src.user.domain.user import User  # only the type checker sees this import

   class UserAdapter(ABC):
       @abstractmethod
       async def find_by_id(self, user_id: str) -> "User | None": ...
   ```

3. **If the value is actually needed at runtime, move the import inside the function/method** — importing at call time rather than at module top level means both modules are already fully loaded by then.

   ```python
   def build_user_adapter() -> UserAdapter:
       from src.user.application.query.get_user_handler import GetUserHandler  # deferred import
       return InProcessUserAdapter(GetUserHandler(...))
   ```

Whereas NestJS's `forwardRef()` tends to be "work around it for now and move on", Python's deferred imports show up explicitly in the code, making it easier to trace later "why was a function-local import used here" — that said, this doesn't justify the cycle itself; option 1 (re-examine the boundary) always takes priority.

---

### Related documents

- [bootstrap.md](bootstrap.md) — where routers are composed in `main.py`
- [layer-architecture.md](layer-architecture.md) — detailed example of `Depends` factories binding an ABC to its implementation
- [cross-domain.md](cross-domain.md) — the Adapter pattern between domains, and why direct imports are avoided
- [directory-structure.md](directory-structure.md) — the overall package tree structure
- [shared-modules.md](shared-modules.md) — where shared code that doesn't belong to any domain lives
