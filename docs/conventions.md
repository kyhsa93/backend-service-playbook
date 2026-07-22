# Conventions

## 1. REST API design principles

### URL structure — resource-centric, plural nouns

A URL names **a resource (a noun), not an action (a verb)**. The HTTP method expresses the action.

```
GET    /orders              list orders
GET    /orders/:orderId     look up a single order
POST   /orders              create an order
PUT    /orders/:orderId     fully update an order
PATCH  /orders/:orderId     partially update an order
DELETE /orders/:orderId     delete an order
```

Wrong:

```
GET  /getOrders      never put a verb in the URL
POST /createOrder    never put a verb in the URL
GET  /order/:id      never use the singular form — always plural
```

### HTTP methods and response codes

| Method | Purpose | Success code | Response body |
|--------|------|----------|----------|
| `GET` | Look up a resource | 200 OK | present |
| `POST` | Create a resource | 201 Created | optional |
| `PUT` | Fully update a resource | 200 OK | present |
| `PATCH` | Partially update a resource | 200 OK | present |
| `DELETE` | Delete a resource | 204 No Content | absent |

### Non-CRUD actions — a sub-resource path

```
POST   /orders/:orderId/cancel     cancel an order
POST   /orders/:orderId/refund     refund an order
POST   /users/:userId/verify-email verify an email
```

### URL naming rules

- **Plural nouns**: `/orders`, `/users` (never singular)
- **kebab-case**: `/order-items`, `/payment-methods`
- **lowercase only**: `/Orders` (wrong) → `/orders` (correct)
- **no trailing slash**: `/orders/` (wrong)

### List lookups — pagination

```
GET /orders?page=0&take=20&status=pending
```

- `page`: starts at 0
- `take`: the page size
- filters: passed as query-string parameters

---

## 2. Commit-message convention

Follows the [Conventional Commits](https://www.conventionalcommits.org/) spec.

### Message structure

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### List of types

| type | Description |
|------|------|
| `feat` | Adds a new feature |
| `fix` | Fixes a bug |
| `refactor` | Restructures code with no behavior change |
| `docs` | Docs-only change |
| `test` | Adds or modifies tests |
| `chore` | Non-code work — build, CI, dependencies, etc. |
| `style` | Code formatting, a change with no effect on behavior |
| `perf` | A performance improvement |

### scope rules

- The scope is **the service's domain name**: `order`, `user`, `payment`, `auth`
- For a change spanning multiple domains, omit the scope or use a higher-level concept
- For a non-code change: `ci`, `deps`, `docker`, etc.

### description rules

- Descriptive, not imperative: "adds", "fixes", "removes"
- No trailing period

### BREAKING CHANGE

```
feat(order)!: change the order response schema

BREAKING CHANGE: renamed GetOrderResponseBody's totalPrice field to totalAmount
```

---

## 3. Branch naming

```
<type>/<scope>-<short-description>
```

Examples: `feat/order-cancel`, `fix/order-status-update`, `docs/cqrs-pattern`

**Rules:**
- Every word is `kebab-case`
- Branch off of `main`
- Never commit/push directly to the `main` branch

### PR workflow

```
1. Create a new branch off of main
2. Do the work, then commit (in Conventional Commits format)
3. Push to the remote
4. Open a PR targeting the main branch
```

### Merge strategy

- **Squash and merge** is the default.
- The remote branch is deleted automatically after merging.

---

## 4. Rate-limiting principles

Apply rate limiting to the API to prevent abuse.

### Basic principles

- **Set a global default**: apply a default limit to every endpoint.
- **Write APIs are stricter than read APIs**: set a lower limit for POST/PUT/DELETE than for GET.
- **Exclude internal endpoints**: exclude things like health checks (`/health/*`) and metrics (`/metrics`) from the limit.
- **Manage limit values via environment variables**: don't hardcode them, so they can be tuned per environment.

### Response headers

Include response headers so the client can see its current status before hitting the limit.

| Header | Description |
|------|------|
| `X-RateLimit-Limit` | The maximum number of requests allowed |
| `X-RateLimit-Remaining` | Requests remaining |
| `X-RateLimit-Reset` | Time remaining until the limit resets |

Return `429 Too Many Requests` when the limit is exceeded.

---

## 5. Method-naming principles

### Service / Handler methods

- Use a verb: `get`, `find`, `create`, `update`, `delete`, `cancel`, `transfer`, etc.
- Always state the return type explicitly

### Repository methods

- Lookup: `find<Noun>s` (the same for a list or a single record)
- Save: `save<Noun>`
- Delete: `delete<Noun>`
- No update method — look it up, modify it via a domain method, then save it via `save<Noun>`
