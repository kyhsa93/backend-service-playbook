# AI Agent Architecture-Compliance Benchmark

This repo already has an objective scorer — the harness (`docs/harness.md`) — that mechanically scores "does this code follow the documented architectural rules" with no human review needed. This doc defines how to reuse that scorer as a **benchmark for measuring an AI coding agent's spec-compliance ability**.

## Why this counts as a benchmark

A typical coding benchmark asks "does it pass the tests." This benchmark asks a different question —
**"can the agent find and read the documented architectural rules on its own, and apply them to a requirement it's never seen before to produce structurally correct code?"** The harness scores exactly whether *structure* — layer placement, dependency direction, CQRS separation, the Domain Event/Outbox pattern — was followed, not whether the business logic happened to be correct. Since automated scoring is the only way to stand in for a human approving "this PR's structure looks fine," this is simply applying what the harness already does for a human to an agent too.

## Task format

The prompt given to the agent contains only these three things — **it never explains how to implement it.**

1. The business rules for a domain **that doesn't exist in this repo yet** (never reuse a name already used for a past validation/scaffolding run — `docs/reference.md`'s Order, Coupon/LoyaltyCategory used for scaffolding validation, and this doc's own example Subscription have all already become "seen" names, so the next benchmark run uses a new name).
2. An instruction to "follow this repo's existing conventions," plus the minimal entry point for where to start reading (`implementations/<lang>/CLAUDE.md`) — no doc path beyond that is given. Whether the agent follows the doc index on its own to find and read the relevant docs is itself what's being measured.
3. The completion criterion: an instruction to run `harness.sh` itself and iterate until it passes.

## Scoring

```bash
bash implementations/<lang>/harness.sh <the project root the agent worked in>
```

- **Score**: nestjs immediately produces a normalized 0-100 score (in the form `A (100/100, raw 630/630)`).
  go/java-springboot/kotlin-springboot/fastapi only print a raw pass/fail count, so normalize it yourself
  as `passed / (passed + failed)` into a ratio comparable across languages.
- **The scorer must be the person running the benchmark, not the agent itself.** Don't just trust the agent's
  self-report of "confirmed harness 100/100" — independently rerun the harness against the agent's worktree
  to confirm it — there have been real cases where a human had to filter out an environment difference
  (e.g. a build failure from missing `node_modules` that looked like a real structural violation).
- **Separately from the structural score, also eyeball the business logic once.** The harness only checks
  "was the pattern followed," not "does the logic inside that pattern match the spec" — a 100 score can
  still coexist with a state-transition condition implemented differently from the spec.

## Run — a single case

Actually ran once on nestjs. Task: "add a Subscription domain — has `ownerId`/`planName`, is `PENDING`
on creation, `activate()` is a simple state transition, `cancel(reason)` matters enough that other parts
may need to react to it (only a hint that what this means should be judged by looking at how this repo
handles this kind of thing), and an already-cancelled one can't be cancelled again." The agent was given
only this rule and told to start from `implementations/nestjs/CLAUDE.md` — `docs/reference.md` and
`scripts/create-domain.js` were never mentioned.

**Result**: the agent followed `CLAUDE.md`'s doc index on its own, discovered `scripts/create-domain.js`
(the scaffolding generator, a Phase 2 deliverable), generated the skeleton with it, then interpreted the
"other parts need to react" hint precisely as "a Domain Event + the Outbox pattern," implementing `cancel()`
to publish a `SubscriptionCancelled` event while `activate()` was a simple event-free transition. The
harness result the benchmark runner independently reran was **A (100/100, raw 630/630)** — matching the
agent's self-report. Reading the domain code directly confirmed the business logic matched the spec exactly
too (an error on re-cancelling an already-cancelled subscription, no event on activate). The existing
Account/Card code was left untouched.

This run demonstrated two things — (1) the harness actually works as a trustworthy scorer even against an
unfamiliar requirement, and (2) this repo's doc structure (the CLAUDE.md index → the relevant architecture
doc → a scaffolding tool if needed) is organized well enough for an agent, not just a human, to follow on
its own.

## Run — comparing across languages

Ran the same task (a Voucher domain: `ownerId`/`faceValue`, `ACTIVE` on issue, `redeem()` is a simple
state transition, `expire()` is given only the hint "matters enough that other parts may need to react,"
an already-redeemed/expired one can't be retried) simultaneously across all 5 languages. Every prompt gave
only `implementations/<lang>/CLAUDE.md` as the entry point, identically, mentioning no other doc path or
scaffolding tool.

| Language | Self-report | Independent re-verification |
|---|---|---|
| nestjs | A (100/100, raw 815/815) | 815/815 — matches |
| fastapi | 854 passed, 0 failed | 854/854 — matches |
| go | 652 passed, 0 failed | 652/652 — matches |
| kotlin-springboot | 1172 passed, 0 failed | 1172/1172 — matches |
| java-springboot | 1404 passed, 0 failed | Initially mismatched at 1433/1 → after root-causing it, unified at 1404/0 — matches |

**All 5 languages got a perfect harness score, and independently arrived at the same architectural
judgment** — each of the 5 agents independently decided, with its own reasoning, to attach a Domain Event
only to `expire()` and not `redeem()` (e.g. applying the same pattern as "a transition nobody reacts to,
like Card's suspend/cancel, has no event; a transition something needs to react to, like Account's
suspend/close, has one"). That means a single doc (the root's `domain-service.md`/each language's
`domain-events.md`) drove the same judgment across a language boundary.

**The principle "never just trust the agent's self-report" actually caught something once** — in
java-springboot, the independent re-verification disagreed with the self-report. The cause wasn't the code,
but a flaw in the harness itself: an intermediate Spotless cache left inside the Gradle-generated `build/`
directory (`build/spotless/spotlessJava/...`, which only partially mirrors the source tree) was being
scanned by the harness as if it were real source, producing a false "no layer directory" positive. Deleting
`build/` and re-verifying matched the self-report exactly.

**The benchmark found 3 real tooling regressions in this repo itself**:
- go/kotlin-springboot's scaffolding generators (`scripts/create-domain*`) were producing code that
  violated the `repository-naming` harness rule (unifying `find<Noun>s`/`save<Noun>`, forbidding
  `findByX`/`findAll`/a bare `save`) that was the latest one at the time — the rule had been added but the
  generator was never updated. Both languages found and fixed it themselves during the benchmark run, and
  the generator itself was later fixed too to block the regression.
- The java-springboot harness's file collector (`JavaFiles.java`) excluded only `test`/`.git`, not `build`,
  causing the false positive described above — kotlin-springboot's equivalent collector (`KtFiles.kt`)
  already excluded `build`, so the implementations were inconsistent across languages; unified by adding the
  `build` exclusion.
- The fastapi harness's `directory-structure` rule walked directly under `src/` via `os.listdir` without
  going through `SKIP_DIRS` (the shared exclusion list other rules use, which includes `__pycache__`),
  mistaking a pytest-generated `src/__pycache__/` for a layerless domain folder and producing a false
  positive — this rule was also fixed to use `SKIP_DIRS`.

## Run — difficulty level 2 (2 Aggregates in the same BC + a Domain Service)

To make up for level 1 (Voucher) having no discriminating power since every language got a perfect score,
ran a task requiring one more decision point, simultaneously across all 5 languages. Task: add
**Booking** (`ownerId`/`seatCount`, `PENDING` on creation, `confirm()` is a simple transition to
`CONFIRMED`) and **Cancellation** (references the original booking via `bookingId`, and the `seatCount`
being cancelled), where a cancellation request is only accepted if (1) the original Booking is `CONFIRMED`
and (2) the requested seatCount doesn't exceed the original seatCount — if either is violated, **the
request itself is never created**. This rule can't be judged by looking at just one Aggregate (Booking
doesn't know about a cancellation attempt, and Cancellation doesn't know the original booking's
status/quantity), so it needs a Domain Service — the only precedent in this repo is Payment/Refund's
`RefundEligibilityService`. The prompt never mentioned this precedent.

| Language | Self-report | Independent re-verification |
|---|---|---|
| nestjs | Initially A(96/100) → self-fixed → A(100/100, raw 815/815) | 815/815 — matches |
| go | 671 passed, 0 failed | 671/671 — matches |
| fastapi | 872 passed, 0 failed | 872/872 — matches |
| java-springboot | 1494 passed, 0 failed | 1494/1494 — matches |
| kotlin-springboot | 1246 passed, 0 failed | 1246/1246 — matches |

**All 5 languages independently arrived at exactly the same design judgment.** Every one found
`RefundEligibilityService` as the precedent and separated the judgment logic into a stateless Domain
Service, and **each even detected the difference from the Refund pattern on its own** — a Refund is still
saved with a `REJECTED` status when rejected, but since this task explicitly stated "the request itself is
never created," all 5 languages changed the Domain Service to throw an exception immediately instead of
returning a value (a decision object), implementing the Application layer so it never reaches the save call
at all on failure. That every language, run independently, caught this subtle spec distinction the same way
says the guidance in `domain-service.md` is communicated with real precision.

**The difference from level 1**: at level 1, all 5 languages scored perfectly from the start, but at level
2, nestjs initially scored 96 (a real code defect — throwing an exception with a raw string instead of the
enum) before fixing it itself — raising the difficulty by one notch produced the first case of "not
perfect from the start." Just bumping the task difficulty by one step was enough to create discriminating
power.

## Run — difficulty level 3 (needs an Adapter that synchronously looks up another BC)

Task: creating a **Membership** (`accountId`/`ownerId`/`tier`) requires that the referenced **Account**
(an already-existing, different BC) not be suspended or closed — creation itself is rejected for such an
account. `cancel()` is a simple transition. The prompt never mentioned terms like "Adapter" or "ACL" at
all — it only stated, as a plain sentence, the fact that "another already-existing part's status must be
checked."

| Language | Self-report | Independent re-verification |
|---|---|---|
| nestjs | A(100/100, raw 815/815) | 815/815 — matches |
| go | 637 passed, 0 failed | 637/637 — matches |
| fastapi | 832 passed, 0 failed | 832/832 — matches |
| java-springboot | 1407 passed, 0 failed | Initially mismatched at 1377 (0 failed the same) → after root-causing a harness flaw, unified at 1377 regardless of build/ |
| kotlin-springboot | 1147 passed, 0 failed | 1147/1147 — matches, separately confirmed BUILD SUCCESSFUL (173 tests, 0 failed/0 error) |

**All 5 languages picked exactly the synchronous Adapter/ACL pattern** — they found the precedent where
Card/Payment already look up Account (an `AccountAdapter`/`AccountAdapterImpl`-style class) and mirrored it
directly, and every one even kept the ACL principle of "never expose Account's status enum as-is — translate
it into something like a boolean before passing it along." The java agent went one step further, reading
ahead in an existing code comment that Card already uses the class name `AccountAdapterImpl`, so reusing
that same name would collide as a Spring Bean name — and sidestepped it by naming its own
`MembershipAccountAdapterImpl`.

**Independent verification caught a harness bug again** — in java-springboot, the self-report (1407) and the
independent re-verification (1377) disagreed again. This time the cause was that the `build/` scan issue
fixed at level 1 also independently existed in **3 other rules** (`PackageStructure.java`,
`NoOrmAutoSyncInProdConfig.java`, `SharedInfra.java`) — each kept its own separate directory-exclusion list,
so fixing just `JavaFiles.java` wasn't enough. They were duplicate-scanning compiled `.class` package
directories and a copied `application.yml` as if they were real source (no false positive resulted, but the
count was inflated, and the structure was, in principle, one where a stale build artifact could lead to a
false pass/fail). Fixed by unifying all 3 rules to also exclude `build`.

## Run — difficulty level 4 (asynchronously reacting to another BC's event)

Task: creating a **StandingOrder** (`accountId`/`ownerId`/`amount`) makes it `ACTIVE`. If the referenced
**Account** is later suspended, the StandingOrder must automatically become `PAUSED`, and if the Account is
closed, it must automatically become `CANCELLED` — this reaction must happen at the moment the Account's
status changes, and must never be triggered by a direct API call on the StandingOrder. Deliberately designed
to contrast with level 3 (Membership)'s "a single synchronous lookup at creation time" — this time the
correct answer is subscribing to an asynchronous Integration Event, not a synchronous Adapter. Verification
was also strengthened: each agent was explicitly told to call the real account suspend/close API and
confirm end-to-end that the reaction actually happens.

| Language | Self-report | Independent re-verification |
|---|---|---|
| nestjs | A(100/100, raw 835/835) | 815/815 (baseline) — matches |
| go | 639 passed, 0 failed | 639/639 — matches |
| fastapi | 829 passed, 0 failed | 829/829 — matches |
| java-springboot | 1407 passed, 0 failed, 1 skip | 1407/1407 — matches |
| kotlin-springboot | 1149 passed, 0 failed | 1149/1149 — matches |

**All 5 languages made a judgment precisely distinguished from level 3** — instead of a synchronous
Adapter, every one chose the asynchronous path of subscribing to the `account.suspended.v1`/
`account.closed.v1` Integration Events Account already publishes, and every one proved it with an E2E test
that actually suspends/closes an account via the real HTTP API and polls until the StandingOrder's status
changes (confirming it actually works, not just that the code compiles).

**The most important finding of this round — a real architectural defect was independently found in 2
languages.** The Card BC was already subscribing to `account.suspended.v1`/`account.closed.v1`, and when
StandingOrder tried to become a second subscriber to the same events, it surfaced the fact that **the root
`domain-events.md`'s principle that "one event can have multiple handler subscribers (1:N)" had never
actually been exercised in java-springboot or fastapi**:
- **java-springboot**: `OutboxConsumer` was building its handler map via
  `Collectors.toMap(eventType, identity())` — a structure where registering a second handler Bean for the
  same eventType would have failed boot outright with `IllegalStateException: Duplicate key`. Fixed by
  switching to `Collectors.groupingBy` so it becomes a `Map<String, List<OutboxEventHandler>>`.
- **fastapi**: `build_event_handlers()` was a `dict[str, EventHandlerFn]` (a single Callable as the value).
  Changed it to `dict[str, list[EventHandlerFn]]` and fixed `OutboxConsumer` to iterate the list and call
  every one — the scaffolding generator (`create_domain.py`) and the `domain-events.md` doc were updated
  together too.
- **go/nestjs had no problem to begin with** — go's `main.go` hand-assembles a `map[string]outbox.Handler`,
  so adding code to call multiple handlers for the same key in sequence was straightforward, and nestjs's
  `EventHandlerRegistry` was already shaped as `Map<string, EventHandlerFn[]>`.
- **kotlin-springboot handled it differently** — it works by adding a second call inside
  `EventHandlerRegistry`'s existing lambda, but unlike java/fastapi it didn't fix the structure itself
  (to be list-based) — it just hardcoded these two subscribers, so a third subscriber to the same event
  later would again need a hand edit — less extensible than java/fastapi's fix.

This was a real defect that existed even while the harness gave a perfect score — because until now, no
domain in this repo had ever actually created a scenario of "two BCs subscribing to the same event at once"
(Card was the only subscriber). This is a genuine bug the benchmark task found not by accident, but because
the **deliberately designed difficulty axis** (1:N fan-out) touched a real, previously untested code path.

## Run — difficulty level 5 (batch + Domain Service combined, within the same BC)

Task: registering a **recurring transfer rule** (`sourceAccountId`/`targetAccountId`/`monthlyAmount`)
automatically transfers every ACTIVE rule on the 1st of each month, with no user API call. On insufficient
balance/a suspended account, only that occurrence is skipped and retried next month, and one rule's failure
must never affect processing of another rule. The question was whether the three axes verified through level
4 (StandingOrder, an asynchronous event reaction) — scheduling/Task Outbox batching (the same pattern as
interest payment/card statements), a Domain Service (the level-2 `RefundEligibilityService` precedent), and
per-rule failure isolation — need to be **combined** in a single task. The withdrawal/deposit accounts were
deliberately kept as the same Aggregate type within the same BC (unlike levels 3/4), so as not to get mixed
up with the "synchronous lookup vs. asynchronous reaction" axis.

To avoid resource contention (running 5 languages' testcontainers at once can overload this machine's Docker
daemon into becoming unresponsive), ran them in waves of two (go+fastapi → nestjs+java-springboot →
kotlin-springboot alone).

| Language | Self-report | Independent re-verification |
|---|---|---|
| go | harness 669/669, unit 75+27 passed, E2E written but not run | harness 669/669 and unit tests match, **E2E 2/4 FAIL** — a real bug found (below) |
| fastapi | harness 850/850, unit 126 passed, E2E written but not run | harness 850/850 and unit tests match, **E2E 5/5 passed** |
| nestjs | A(100/100, raw 855/855), unit 134/134, E2E 60/60 | everything matches |
| java-springboot | (independent verification finished before the self-report — see below) | harness 1477/1477, **initial E2E 2/4 FAIL** → after fixing the bug, re-verified at 191/191 passed |
| kotlin-springboot | harness 1215/1215, full build 179 tests 0 failed | harness 1215/1215 and 179/179 match (though the first re-run attempt showed a false `UP-TO-DATE` from the Gradle cache — confirmed via `--rerun` for a genuine re-execution) |

**Only 3 of the 5 languages (fastapi, nestjs, kotlin-springboot) passed cleanly on the first submission.**
The other 2 (go, java-springboot) had a perfect harness score and perfect unit tests, but a **real bug that
only showed up in an E2E test against real infrastructure** — the clearest example yet of why this
benchmark needs to check "does it actually work," not just "does it follow the structure."

- **go**: running the recurring transfer tried to save a `referenceID := ruleID + "-" + period` (a 32-char
  hex ID + `-YYYY-MM` = 40 chars) into the `transactions.reference_id VARCHAR(36)` column, and Postgres
  rejected it with `value too long for type character varying(36)` — failing on every second month's run (a
  retry scenario). The unit tests use an in-memory fake, so they never go through the column-length
  constraint at all and couldn't catch it. The benchmark's own code wasn't modified — the result was
  recorded as-is (the same convention as this round — not pre-merging infrastructure with no actual use
  site).
- **java-springboot**: `RecurringTransferSchedulingE2ETest` split 3 `@Test` methods per scenario, each
  calling `enqueueMonthlyRecurringTransfers()`, but the scheduler's `dedupId`
  (`TASK_TYPE + "-" + YearMonth.now()`) is always identical within the same month, so it fell inside SQS
  FIFO's 5-minute dedup window — **only the first test method's call actually made it onto the Task Queue,
  and the rest were silently ignored**. The "insufficient-balance rule is skipped, the other rule is
  processed normally" scenario's normal rule was never actually processed at all, yet the
  insufficient-balance assertion happened to pass anyway, which nearly looked like a false success. Since
  the nestjs agent working the same task in parallel had found and avoided exactly this same trap ahead of
  time (bundling every scenario into a single test method), that fact was used to hand the java agent a
  concrete diagnosis, and after merging the 3 `@Test` methods into 1 and fixing it, the re-verification
  passed all 191/191.
- **Cross-contamination between Docker containers (not a real bug, recorded separately)**: at one point
  during verification, java's E2E failed far more severely
  (`PSQLException: FATAL: terminating connection due to administrator command`), and the cause wasn't the
  code — it was that the **nestjs agent's "clean up Docker" finishing step, which had ended at almost the
  same moment, also shut down the Postgres/LocalStack containers java was still using**. Only after
  re-running both agents in a genuinely quiet environment (confirmed via `docker ps`/`ps aux`) was it
  confirmed that only the original narrow failure (the item above) reproduced. This left the lesson that an
  infrastructure-flavored failure (a forced-closed connection, a cascading auth failure) should be suspected
  of coming from a sibling agent's cleanup step before being treated as a confirmed real bug.
- **A side finding**: the go agent ran into a real need, in this task, to wrap multiple Repositories
  (the withdrawal account + the deposit account + the rule) in a single transaction, and actually
  implemented and verified a `context`-based `WithTx`/`TxFromContext`/`QuerierFrom` transaction manager —
  this genuinely validated the design of a previously-open issue (multi-Repository transaction propagation
  had never been implemented). However, since main still has no real use site for it (every existing
  Handler only uses a single Repository), it wasn't merged, to avoid it becoming dead code — only the design
  validation result was recorded on the issue.

## How to extend this

- **Comparing across languages**: run the same task on each of the 5 languages and put the normalized scores
  side by side — this can reveal whether a given language's docs/conventions are easier for an agent to
  follow than another's.
- **Comparing across models**: run the same task with a different model (e.g. a smaller one) and compare
  scores — this becomes an experiment in how much "the ability to read an architecture doc and follow the
  structure" depends on model scale.
- **A task suite**: pre-defining several tasks split by difficulty (a simple CRUD domain / a domain that
  needs a Domain Event / a domain that needs an Adapter synchronously looking up another BC) turns this into
  a repeatable suite you don't have to redesign every time.
- **Regression watch**: rerunning the same task every time this repo's docs/harness change, and confirming
  the score doesn't drop, also lets you watch for whether a docs/harness change accidentally hurt "how easy
  it is for an agent to follow."

## Related docs

- `docs/harness.md` — the harness's own design principles (what it checks and what it doesn't)
- `implementations/<lang>/CLAUDE.md` — the only entry point a benchmark task gives the agent
- `implementations/<lang>/scripts/create-domain*` — a scaffolding tool (a Phase 2 deliverable) the agent
  can discover and use on its own. It's never mentioned directly in the benchmark prompt — discovering it
  is itself part of what's being measured.
