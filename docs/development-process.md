# Development Process — Agent-Role Based

This doc defines a process for running a domain-driven-design-based backend project split into **8 independent agent roles**. It applies the same way regardless of language/framework — the code examples use TypeScript, but the patterns themselves are identical in any language.

Each agent has a clear input and output, and can work independently once given its input.

### Shared conduct rules

Every agent follows these rules when talking with the user:

- Never ask too many questions at once. Ask 3-5 questions per sub-step at most, then continue with follow-up questions once you get an answer.
- If the user's answer is vague or insufficient, ask again with a concrete example.
- For anything the user can't answer, propose options based on domain knowledge.
- At the end of each step, write up the deliverable as a markdown doc and get the user's confirmation.
- If the user asks for a change, apply it and get confirmation again.

## Agent composition

```
                   Orchestrator Agent
      (coordinates the overall flow / hands off deliverables / quality gate)
      │      │      │      │      │      │      │      │
      ▼      ▼      ▼      ▼      ▼      ▼      ▼      ▼
    [RA]   [SD]   [DM]   [TD]   [TE]   [IM]   [VA]   [LA]

RA = Requirements Analyst    SD = Strategic Designer
DM = Domain Modeler          TD = Tactical Designer
TE = Test Engineer            IM = Implementer
VA = Validator               LA = Legacy Analyzer
```

### Deliverable flow

```
User request ──▶ [RA] ──requirements spec──▶ [SD] ──strategic design doc──▶ [DM] ──domain model──▶ [TD] ──tactical design doc──▶ [TE] ──test code──▶ [IM] ──implementation code──▶ [VA] ──verification report
```

### Workflow by task type

Not every task needs to go through the whole process (RA-VA). On receiving a task request, the Orchestrator first judges the task type, then enters the workflow that fits it.

| Task type | Workflow | How to judge |
|-----------|-----------|----------|
| Building a new domain | RA → SD → DM → TD → **(user confirmation)** → TE → IM → VA | Building a new domain/feature from scratch |
| Refactoring an existing domain | DM → TD → **(user confirmation)** → TE → IM → VA | Converting existing code to the DDD structure, or changing the architecture |
| Modifying a legacy feature | LA → DM → TD → **(user confirmation)** → TE → IM → VA | Modifying a feature in legacy code, refactoring that Vertical Slice to match the guide at the same time |
| A bug fix / a small change | TE → IM → VA | A bug fix, adding a field, a config change, etc. — no design change |

**Hard-gate rules**:

- **Design first, implement later**: if the chosen workflow includes a design step (DM, TD), write the design deliverable first.
- **User confirmation is required**: only enter implementation (IM) once the design deliverable has been shown to the user and confirmed.
- **Never write code without confirmation**: even if the user says "go ahead," confirming the design deliverable has to come first.
- **Verification is required**: after finishing the implementation, do a self-verification (VA) based on [checklist.md](checklist.md).
- **The guide takes priority**: if the existing project's code pattern conflicts with a guide rule, follow the guide.
- **Pre-verify a design deliverable**: before asking the user to confirm a design deliverable, self-verify it against the relevant [checklist.md](checklist.md) items. If there's a violation, fix it before presenting it to the user. No checklist violation should remain by the time the user confirms it.

**What to pre-verify (per design stage)**:

| Design stage | Checklist STEP to verify |
|-----------|----------------------|
| DM (domain modeling) | STEP 2 (the Domain layer), STEP 4 (the Repository pattern) |
| TD (tactical design) | STEP 1 (file structure and naming), STEP 3 (layer architecture), STEP 5 (error handling) |

The checklist STEP numbers may have language-specific STEPs added in each language's `checklist.md` (e.g. `implementations/<lang>/docs/checklist.md`) — map against the actual project's checklist.md table of contents.

**When the task type is ambiguous**: state it explicitly to the user and agree on it — "I've judged this task as {type}. I'll proceed with the {workflow} workflow."

### Modifying a legacy feature — the Vertical Slice refactoring strategy

When modifying a feature in legacy code, refactor to match the guide, at the same time, within the scope of that feature's **Vertical Slice (Interface → Application → Domain → Infrastructure)**.

#### Principles

- **Only clean up the path you're fixing**: only the direct call path of the requested feature is in scope for refactoring. Don't widen the scope with "since we're fixing this feature, let's also fix that related thing."
- **Treat the feature fix + refactor as one task**: don't split the refactor out separately — deliver it together with the feature fix.
- **The guide takes priority**: convert the inside of the Slice to the guide's structure. If the existing code pattern conflicts with the guide, follow the guide.

#### Scope rules

| Allowed | Forbidden |
|------|------|
| Refactoring that feature's Controller → Service → Domain → Repository path | Refactoring another API path in the same module that's unrelated to this feature fix |
| Changing the DTO, Command, Query, Event structures used in that Slice | Unilaterally changing shared code also used by other modules |
| Redesigning that Aggregate's domain model | Changing another Aggregate's domain model |

#### External interface compatibility

- Even while changing the Slice's internal structure, **the entry point (public API) other modules call must keep its existing contract**.
- If changing the entry point is unavoidable, update the callers too, and state the blast radius to the user explicitly.
- Example: if another module calls `OrderService.findById()` directly, even if you split the internals into CQRS, either keep the existing call working or update the caller together with it.

#### Applying the workflow

1. Receive the feature-fix request
2. **LA**: analyze the current code — trace the Slice path, analyze the guide gap, identify external dependencies → produces an analysis report
3. **DM**: based on LA's deliverable, redesign the domain model within the Slice's scope to match the guide
4. **TD**: write the tactical design doc (stating both the feature fix and the refactor scope)
5. User confirmation
6. **TE → IM → VA**: implementation and verification

#### Being aware of the transitional coexistence

- Applying this strategy means the legacy structure and the guide's structure coexist within one project.
- This is an "ongoing incremental migration" — a domain that gets modified more often converts to the new structure faster.
- Code that's almost never touched is excluded from refactoring, to keep this cost-effective.

---

## Agent 0: Orchestrator

### Role

The overall agent that coordinates the whole development process. Delegates work to each agent and manages the quality gate on deliverables.

### Responsibilities

1. **Judge the task type**: analyze the user's request to judge the task type (new development / refactoring / a legacy feature fix / a bug fix), and pick the matching workflow.
2. **Control the flow**: call the workflow's agents in order, and hand each deliverable off to the next agent.
3. **The quality gate**: only proceed to the next step once the user has confirmed each agent's deliverable. Confirming the design deliverable before entering implementation (IM) is required in particular.
4. **Passing context**: summarize the previous agent's deliverable and provide it as input to the next agent.
5. **Routing feedback**: pass the user's change request to the relevant agent, and regenerate any downstream deliverable it affects.
6. **Reporting progress**: tell the user which agent is currently doing what.

### Process operating rules

- Only call the next agent once the user has confirmed the current agent's deliverable.
- If the user asks for a change, instruct the relevant agent to redo the work.
- Report progress in the form "{agent name} is currently doing {task}."
- When asking the user to confirm a deliverable, state explicitly: "If this looks right, I'll move to the next step. Let me know if anything needs changing."

---

## Agent 1: Requirements Analyst

### Role

An agent that talks with the user to systematically analyze and flesh out business requirements.

### Input

- The user's project description and requirements (free-form)

### Output

- A requirements spec (functional/non-functional requirements, acceptance criteria, priority)
- A use-case list and scenario doc
- A constraints summary table

### Procedure

#### 1.1 Understanding the business goal

Ask the user:

- "What service/system are you trying to build? Tell me about the project's background."
- "What's the core problem (pain point) this project is meant to solve?"
- "Do you have a success criterion? (e.g. cutting order-processing time by 50%, reaching 100k MAU, etc.)"

Once you get the answers, summarize the following and confirm with the user:

- A summary of the project's background (1-2 sentences)
- A list of core problems
- A list of success criteria (include quantitative metrics if there are any)

#### 1.2 Identifying stakeholders and gathering requirements

Ask the user:

- "What types of users use this system? (e.g. regular users, admins, partners, etc.)"
- "What does each user type mainly do in the system?"
- "Is there an existing system in use? If so, what problems does it have?"

Once you get the answers, summarize them in this format:

```
| User type | Role description | Main activities | Expectations |
|------------|----------|----------|----------|
| ...        | ...      | ...      | ...      |
```

#### 1.3 Defining use cases

Based on the answers from 1.1-1.2, first propose a use-case list, then confirm with the user that nothing's missing.

For each confirmed use case, write it up in this format:

```
#### UC-001: {use case name}
- **Actor**: {who carries it out}
- **Preconditions**: {what must hold before the use case runs}
- **Main flow (Happy Path)**:
  1. ...
  2. ...
- **Exception flows**:
  - E1: {the exceptional situation} → {how it's handled}
- **Postconditions**: {the system's state after the use case completes}
```

If there are 5 or more use cases, don't write them all at once — write the 3-5 core use cases first, get them confirmed, then continue with the rest.

#### 1.4 Fleshing out requirements and deciding priority

Derive functional requirements from the use cases, and ask the user further questions for non-functional requirements:

- "Are there performance requirements? (e.g. response time under 200ms, 1000 concurrent connections, etc.)"
- "Are there security requirements? (e.g. encrypting personal info, 2FA, etc.)"
- "Are there scalability requirements? (e.g. future global expansion, multi-tenancy, etc.)"

State an acceptance criterion for each requirement:

```
#### FR-001: {functional requirement name}
- **Description**: ...
- **Acceptance criteria**:
  - [ ] ...
  - [ ] ...
- **Priority**: Must / Should / Could / Won't
```

Classify priority using the MoSCoW technique, explain the reasoning to the user, then agree on it.

#### 1.5 Summarizing constraints

Ask the user:

- "Is there a fixed tech stack you have to use? (language, framework, DB, cloud, etc.)"
- "Are there external systems you need to integrate with? (payments, notifications, a CRM, etc.)"
- "Are there schedule constraints? (an MVP launch date, milestones, etc.)"
- "Are there regulatory or compliance requirements? (privacy law, PCI-DSS, etc.)"
- "What's the expected traffic scale?"

Mark any item with no answer as "no constraint," but ask about it again later if a decision is needed at a later stage.

#### 1.6 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- A requirements spec (functional/non-functional requirements, acceptance criteria, priority)
- A use-case list and scenario doc
- A constraints summary table

---

## Agent 2: Strategic Designer

### Role

An agent that analyzes the domain's problem space based on the requirements, identifies subdomains and Bounded Contexts, and designs the system's strategic structure.

### Input

- **Agent 1's deliverable**: the requirements spec, use-case list, constraints summary table

### Output

- A problem-space definition
- A subdomain classification table (Core / Supporting / Generic, including the implementation strategy)
- Bounded Context definitions (each Context's responsibility, core concepts, owning subdomain)
- A Context Map (including the relationship type and why it was chosen)

### Procedure

#### 2.1 Defining the problem space

- Synthesize domain knowledge from the requirements spec to define the problem space.
- Structure the core goals and the problems to solve, and present them to the user.
- Write up the problem space in this format:

```
### Problem-space definition
- **Domain**: {domain name}
- **Core problem**: {a summary of the problem to solve}
- **Related areas**: {list the business/functional areas included in the domain}
```

#### 2.2 Subdomain classification

- Classify the areas identified in the problem space into subdomains.
- Explain the classification criteria to the user and agree on them:
  - **Core Domain**: this business's own competitive edge. Must be built in-house, and needs the most investment.
  - **Supporting Subdomain**: supports the Core, but isn't a differentiator. Build in-house, but can be simpler than Core.
  - **Generic Subdomain**: an area solvable generically. Prefer adopting an existing solution.
- Write up the classification result in this format:

```
| Subdomain | Type | Description | Implementation strategy |
|-----------|------|------|----------|
| ...       | Core | ...  | build in-house  |
| ...       | Supporting | ... | build in-house (simplified) |
| ...       | Generic | ... | adopt an external solution |
```

#### 2.3 Identifying Bounded Contexts

- Identify Bounded Contexts within each subdomain.
- Split Context boundaries using these criteria:
  - Where the same term is used with a different meaning (e.g. "Product" means display info in the catalog, but a purchased line item in orders)
  - A unit that must be independently deployable/changeable
  - An area a different team owns
- Ask the user "is there a term that means something different depending on context?" to validate the boundary.
- Write up each Context in this format:

```
#### {Context name} Context
- **Responsibility**: {the core business this Context owns}
- **Core concepts**: {the key domain terms within this Context}
- **Owning subdomain**: {Core / Supporting / Generic}
```

#### 2.4 Drawing up the Context Map

- Define the relationships between Bounded Contexts.
- For each relationship, pick whichever of these types fits and explain why:
  - **Partnership**: two teams succeed/fail together, so they collaborate closely
  - **Shared Kernel**: the two Contexts share part of a model
  - **Customer-Supplier**: the downstream (Customer) communicates requirements to the upstream (Supplier)
  - **Conformist**: the downstream follows the upstream model as-is
  - **Anticorruption Layer**: the downstream adds a translation layer to keep the upstream model's contamination out
  - **Open Host Service / Published Language**: the upstream provides a published API/protocol
- Express the Context Map as a text diagram:

```
[Order Context] --Customer-Supplier--> [Payment Context]
[Order Context] --ACL--> [external shipping API]
[Product Context] --OHS/PL--> [Order Context]
```

#### 2.5 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- A problem-space definition
- A subdomain classification table (Core / Supporting / Generic, including the implementation strategy)
- Bounded Context definitions (each Context's responsibility, core concepts, owning subdomain)
- A Context Map (including the relationship type and why it was chosen)

---

## Agent 3: Domain Modeler

### Role

An agent that designs the domain model within each Bounded Context by talking with the user via Event Storming.

### Input

- **Agent 1's deliverable**: the use-case list and scenario doc
- **Agent 2's deliverable**: the subdomain classification table, Bounded Context definitions, the Context Map

### Output

- The Event Storming result mapping table
- The Ubiquitous Language glossary
- The domain model structure per Aggregate (Entity, Value Object, relationships)
- A detailed Domain Event list
- Business rules and invariant specs per Aggregate

### Conduct rule

- Work on the Core Domain's Bounded Context first. Once one Context's modeling is done, move to the next Context.

### Procedure

#### 3.1 Event Storming

Run interactive Event Storming with the user. Derive things one at a time in this order, confirming with the user at each step that nothing's missing:

**Step 1 — Deriving Domain Events**

- Ask "please list every important thing that happens (an event) in this Context."
- Once the user answers, normalize them to past-tense verbs (e.g. `OrderPlaced`, `PaymentCompleted`).
- Sort them chronologically, and cross-check against the use cases to confirm no event is missing.

**Step 2 — Deriving Commands**

- For each event, ask "what action (command) causes this event?"
- Normalize to imperative verbs (e.g. `PlaceOrder`, `ProcessPayment`).

**Step 3 — Deriving Actors**

- For each Command, identify "who (or what) carries out this action?"
- Distinguish user types (defined in the requirements spec) from a system/timer/policy.

**Step 4 — Deriving Aggregates**

- Analyze the grouping of Commands and Events and ask "what's the core object that handles this command and produces this event?"
- Define each Aggregate's name and responsibility.

**Step 5 — Deriving Policies**

- Ask "is there an action that automatically follows once a certain event happens?"
- Write up a Policy in the form `When {Event} Then {Command}`.

**Step 6 — Deriving External Systems**

- Ask "are there any points that integrate with an external system? (a payment gateway, a notification service, an external API, etc.)"
- Identify each integration point's direction (calling out / receiving) and data format.

**Step 7 — Deriving Read Models**

- Ask "what information does the user need to look up on screen? What data do they base a decision on?"
- List the data items each Read Model needs.

**Step 8 — Identifying Hot Spots**

- Flag anything ambiguous or that needs a decision, found along the way, as a red-marked Hot Spot.
- For each Hot Spot, ask the user for a resolution, or propose options.

Write up the Event Storming result in this format:

```
| Actor | Command | Aggregate | Domain Event | Policy | External System |
|-------|---------|-----------|-------------|--------|----------------|
| ...   | ...     | ...       | ...         | ...    | ...            |
```

#### 3.2 Defining the Ubiquitous Language

- Collect every term that came out of Event Storming.
- Write up the glossary in this format:

```
| Term (English) | Term (native language) | Definition | Owning Context | Notes (distinguishing synonyms) |
|------------|-----------|------|-------------|----------------------|
| Order      | ...      | ... | Order Context | Distinguish from "Purchase": a purchase is... |
```

- Always state explicitly when the same word is used with a different meaning in a different Context.

#### 3.3 Identifying core domain objects

- Flesh out the internal composition based on the Aggregates derived from Event Storming.
- Write up each Aggregate in this format:

```
#### {Aggregate name} Aggregate
- **Aggregate Root**: {the Root Entity's name}
- **Entities**:
  - {Entity name}: {description, identifier}
- **Value Objects**:
  - {VO name}: {list of attributes, equality criterion}
- **Relationships**:
  - {a description of the relationship between Entities/VOs}
```

- Explain the criteria for distinguishing Entity from Value Object to the user:
  - "Does this object need to be tracked by a unique ID?" → Entity
  - "Is it fine to treat it as the same thing when its attribute values are equal?" → Value Object

#### 3.4 Detailed Domain Event definitions

- Define each Domain Event in detail, in this format:

```
| Event name | When it fires | Data it includes | Follow-up processing (Policy/subscribers) |
|---------|----------|-----------|----------------------|
| OrderPlaced | An order was created successfully | orderId, userId, items[], totalAmount, placedAt | → deduct inventory, request payment |
```

#### 3.5 Summarizing business rules and invariants

- For each Aggregate, write up its invariants in this format:

```
#### {Aggregate name}'s invariants
- INV-001: {a description of the invariant} (e.g. "an order's total must always be greater than 0")
  - On violation: {throw an exception / reject / a compensating transaction}
- INV-002: ...
```

- Confirm with the user: "does this rule always have to hold with no exception? Are there exception cases?"

#### 3.6 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- The Event Storming result mapping table
- The Ubiquitous Language glossary
- The domain model structure per Aggregate (Entity, Value Object, relationships)
- A detailed Domain Event list
- Business rules and invariant specs per Aggregate

---

## Agent 4: Tactical Designer

### Role

An agent that carries out a concrete technical design, at an implementable level, based on the domain model.

### Input

- **Agent 1's deliverable**: the use-case list (including priority)
- **Agent 3's deliverable**: the domain model per Aggregate, the Domain Event list, business rules/invariant specs

### Output

- Aggregate design docs (the Root, its boundary, internal composition, external references, invariants)
- A Repository interface spec
- A Domain Service spec
- An Application Service spec (mapping to use cases, the processing flow, the transaction scope)
- Whether Command/Query separation is applied, and its design (if applied)
- An Event flow diagram (including Sagas, compensating transactions)

### Procedure

#### 4.1 Aggregate design

- Settle each Aggregate's boundary and decide:
  - Choosing the Aggregate Root: internal objects are only ever accessed through the Root from outside.
  - The Aggregate's size: design it as the smallest unit that must change within one transaction.
  - References between Aggregates: never reference another Aggregate directly — only by ID.
- If an Aggregate is getting bloated, propose splitting it to the user, explaining the criterion:
  - "Does this data always have to change together, or can it change independently?"
- Write up the design result in this format:

```
#### {Aggregate name}
- **Root**: {the Root Entity}
- **Internal Entities**: [{list of Entities}]
- **Internal Value Objects**: [{list of VOs}]
- **External references (by ID)**: [{the IDs of other Aggregates it references}]
- **Creation rules**: {the conditions checked in the Factory or constructor}
- **Invariants**: [{INV-001, INV-002, ...}]
```

#### 4.2 Repository design

- Define a Repository interface per Aggregate Root.
- Put only the interface in the domain layer; place the implementation in the infrastructure layer.
- Write it up in this format:

```
#### {Aggregate name}Repository (the interface — the domain layer)
- find{Aggregate}s(query): { {aggregate}s: {Aggregate}[]; count: number }
- save{Aggregate}({Aggregate}): void
- delete{Aggregate}({id}): void
```

- Define only a single `find<Noun>s` for lookups. For a single-record lookup, the Service uses the `take: 1` + `.then(r => r.<noun>s.pop())` pattern.
- If there start to be many read-only methods, propose splitting out a separate Query interface.

#### 4.3 Domain Service design

- Identify logic that needs a Domain Service, using these criteria:
  - Domain logic that doesn't belong to a single Aggregate
  - Logic that needs to read several Aggregates to make a judgment
  - Domain logic that involves calling an external service
- Write up each Domain Service in this format:

```
#### {ServiceName}
- **Responsibility**: {a description of the domain logic this service carries out}
- **Input**: {parameters}
- **Output**: {the return value}
- **Aggregates/Repositories it uses**: [{list}]
```

#### 4.4 Application Service design

- Define Application Services, mapped to the use-case list.
- Write up each service in this format:

```
#### {UseCaseName}Service
- **Use case**: UC-{number}
- **Processing flow**:
  1. {validate the input}
  2. {look up the Aggregate from the Repository}
  3. {call a domain method on the Aggregate (business logic)}
  4. {save the Aggregate}
  5. {publish a Domain Event}
- **Transaction scope**: {from where to where}
- **On failure**: {rollback / a compensating transaction}
```

#### 4.5 Reviewing Command/Query separation

- Judge whether to adopt CQRS using these criteria, and explain the reasoning to the user:
  - Is the read/write load ratio very different?
  - Does a lookup need to combine several Aggregates?
  - Is separate read-performance optimization needed?
- If adopting it:
  - Command: processed through the domain model
  - Query: processed through a separate Read Model / Projection
  - Decide how Command and Query data stay in sync (sync/async)
- If not adopting it: state why, and design it so it can be switched to later if needed.

#### 4.6 Event-flow design

- Design the overall event flow based on the domain model's Domain Events and Policies.
- Decide how each event is processed:
  - **Synchronous processing**: processed immediately within the same transaction (when strong consistency is needed)
  - **Asynchronous processing**: processed separately via a message queue (when eventual consistency is acceptable)
- If there's a process spanning multiple Aggregates:
  - **Saga (Choreography)**: handled via event chaining. Define a compensating transaction for each step.
  - **Saga (Orchestration)**: a central orchestrator controls the flow.
  - Explain the selection criteria to the user and agree on it.
- Write up the event flow in this format:

```
#### Saga: {process name}
1. {Command} → {Aggregate} → {Event} [sync/async]
   - Compensation on failure: {a compensating Command}
2. {Policy}: When {Event} → {the next Command}
   ...
```

- Review whether to adopt Event Sourcing. Explain these criteria to the user:
  - Is history tracking a core business requirement?
  - Is an audit log legally required?
  - Is state reconstruction needed often?

#### 4.7 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- A file-structure tree (domain/, application/, infrastructure/, interface/) — see `architecture/directory-structure.md` for the exact per-language layout
- The module/package-level composition (must be organized independently per domain)
- The dependency-injection setup (interface → implementation binding)
- Aggregate design docs (the Root, its boundary, internal composition, external references, invariants)
- A Repository interface spec
- A Domain Service spec
- An Application Service spec (mapping to use cases, the processing flow, the transaction scope)
- Whether Command/Query separation is applied, and its design (if applied)
- An Event flow diagram (including Sagas, compensating transactions)

---

## Agent 5: Test Engineer

### Role

An agent that writes test code **before implementation**, based on the design deliverables and requirements. Hands off to the Implementer while the tests are failing, and the Implementer's job is to make them pass (a Test-First approach).

**When writing tests, always follow [conventions.md](conventions.md)'s testing patterns.**

### Input

- **Agent 1's deliverable**: the use-case list (including acceptance criteria)
- **Agent 3's deliverable**: the domain model per Aggregate, business rules/invariant specs, the Domain Event list
- **Agent 4's deliverable**: Aggregate design docs, the Repository interface spec, the Application Service spec

### Output

- Test code (unit/integration/E2E — all failing)
- A test-list doc (test name, what it verifies, expected result)

### Procedure

#### 5.1 Establishing a test plan

Write up a test list based on the requirements' acceptance criteria and the domain model's invariants.

```
| Test name | Type | What it verifies | Expected result |
|---------|------|----------|----------|
| createOrder_when_ItemsEmpty_then_Throw | unit | Order's invariant | throws an exception |
| cancelOrder_when_AlreadyCancelled_then_Throw | unit | Order.cancel() | throws an exception |
| cancelOrder_then_OrderCancelledEventCollected | unit | Domain Event collection | the event exists |
| getOrders_then_ReturnPagedResult | integration | OrderQueryImpl | a paginated result |
| POST /orders → 201 | E2E | the order-creation API | 201 Created |
| POST /orders/:id/cancel → 204 | E2E | the order-cancellation API | 204 No Content |
```

Show the test list to the user and confirm nothing's missing.

#### 5.2 Writing unit tests — the Domain layer

Write tests for the Aggregate, Value Object, and Domain Event. Write them as **pure language code with no framework dependency** (since the Domain layer under test is itself framework-independent, the test itself should also be able to instantiate it directly and verify it, with no framework).

- Verify invariants at Aggregate creation (invalid input → an exception)
- Business rules in a state-changing method (condition met/not met)
- Value Object equality comparison
- Whether a Domain Event was collected

```typescript
// order/domain/order.spec.ts (conceptual — the real test framework follows each language's own convention)
describe('Order', () => {
  it('createOrder_when_ItemsEmpty_then_Throw', () => {
    expect(() => new Order({
      userId: 'user-1',
      items: [],
      status: 'pending'
    })).toThrow('An order must have at least one item.')
  })

  it('cancelOrder_when_AlreadyCancelled_then_Throw', () => {
    const order = createTestOrder({ status: 'cancelled' })
    expect(() => order.cancel('changed my mind')).toThrow('This order has already been cancelled.')
  })

  it('cancelOrder_then_OrderCancelledEventCollected', () => {
    const order = createTestOrder({ status: 'pending' })
    order.cancel('changed my mind')
    expect(order.domainEvents).toHaveLength(1)
    expect(order.domainEvents[0]).toBeInstanceOf(OrderCancelled)
  })
})
```

#### 5.3 Writing unit tests — the Application layer

Test the Command Service's use-case flow. Replace the Repository and transaction manager with a **mock/fake**, verifying only the coordination logic with no real DB.

```typescript
// order/application/command/order-command-service.spec.ts (conceptual)
describe('OrderCommandService', () => {
  let service: OrderCommandService
  let orderRepository: MockedRepository<OrderRepository>

  beforeEach(() => {
    orderRepository = createMockRepository<OrderRepository>()
    service = new OrderCommandService(orderRepository)
  })

  it('cancelOrder_when_OrderNotFound_then_Throw', async () => {
    orderRepository.findOrders.mockResolvedValue({ orders: [], count: 0 })
    await expect(service.cancelOrder({ orderId: 'non-existent', reason: 'changed my mind' }))
      .rejects.toThrow('Order not found.')
  })
})
```

The actual mocking framework (Jest, Mockito, MockK, unittest.mock, testify/mock, etc.) follows each language's own convention.

#### 5.4 Writing integration tests

Test the Repository implementation's and Query implementation's real DB integration. Where possible, verify against something close to a real DB, like an in-memory DB or testcontainers.

```typescript
// order/infrastructure/order-query-impl.spec.ts (conceptual)
describe('OrderQueryImpl (integration)', () => {
  let queryImpl: OrderQueryImpl

  beforeAll(async () => {
    // set up the test environment against a real DB (in-memory or testcontainers)
    queryImpl = await createOrderQueryImplForTest()
  })

  it('getOrders_then_ReturnPagedResult', async () => {
    // insert test data, then verify the lookup result
  })

  afterAll(async () => {
    // clean up the test DB
  })
})
```

#### 5.5 Writing E2E tests

Verify the use case's whole flow (an HTTP request → the response).

```typescript
// test/order.e2e-spec.ts (conceptual)
describe('Order API (e2e)', () => {
  let app: TestApp

  beforeAll(async () => {
    app = await startTestApp()
  })

  it('POST /orders → 201 Created', () => {
    return app.request()
      .post('/orders')
      .send({ userId: 'user-1', items: [{ itemId: 1, name: 'a product', price: 10000, quantity: 1 }] })
      .expect(201)
  })

  it('POST /orders/:id/cancel → 204 No Content', async () => {
    // create an order, then cancel it
  })

  afterAll(() => app.close())
})
```

#### 5.6 Confirming test execution

Confirm every test is **in a failing state**. Since there's no implementation code yet, it's correct for the tests to fail.

- Unit tests: a compile error or runtime error, since the Aggregate/Service class doesn't exist
- Integration tests: fail, since the Repository implementation doesn't exist
- E2E tests: fail, since the Controller/router doesn't exist

**Get the user to confirm the test list and code at this point.**

#### 5.7 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- The test list (test name, type, what it verifies, expected result)
- The list of test code files

---

## Agent 6: Implementer

> **The goal is to make the tests the Test Engineer wrote pass.** Once every test passes, that slice's implementation is done.

### Role

An agent that generates code based on the tactical design doc. Implements it per use case, via Vertical Slicing.

**When implementing, always follow the architectural rules in [architecture/](architecture/) and the coding conventions in [conventions.md](conventions.md).**

### Input

- **Agent 1's deliverable**: the use-case list (with priority), the constraints summary table
- **Agent 3's deliverable**: the Ubiquitous Language glossary
- **Agent 4's deliverable**: Aggregate design docs, the Repository interface spec, the Application Service spec, the Event flow diagram
- **Agent 5's deliverable**: test code (all failing)

### Output

- The project structure and architecture guide (package composition, inter-layer dependency rules)
- Implemented source code (per layer) — **in a state where every test passes**
- An API spec (endpoints, request/response schemas, error codes)
- A DB schema definition
- An infrastructure-integration spec (message-queue topics, external API integration info)

### Procedure

#### 6.1 Implementation strategy — Vertical Slicing

Implement **per use case (vertically)**, not **per layer (horizontally)**.

**A horizontal approach (✗ — don't use this)**:

```
Pass 1: implement every Aggregate Root → Pass 2: implement every Repository → Pass 3: implement every Service → Pass 4: implement every Controller
```

**A vertical approach (✓ — Vertical Slicing)**:

```
Slice 1: implement every layer of the "create order" use case at once
  → the Order Aggregate + OrderRepository + CreateOrderCommandHandler + the Controller's POST /orders

Slice 2: implement every layer of the "look up orders" use case at once
  → GetOrdersQueryHandler + the Controller's GET /orders

Slice 3: implement every layer of the "cancel order" use case at once
  → Order.cancel() domain method + CancelOrderCommandHandler + the Controller's POST /orders/:id/cancel
```

**Slicing principles:**

- **1 slice = 1 use case**: include every layer that use case needs — Domain → Application → Infrastructure → Interface.
- **Priority-based order**: slice the Must use cases first, following MoSCoW priority.
- **Confirm it works per slice**: after finishing each slice, the API must be callable. Never move to the next slice while one is partially implemented.
- **Establish the project skeleton in the first slice**: the first slice also sets up the project's base structure — the directory layout, DI wiring, etc.

**Establishing a slice plan:**
Propose a slice order to the user in this format, and agree on it:

```
| Slice | Use case | Files included | Priority |
|---------|----------|----------|---------|
| 1       | UC-001 create order | Order, OrderItem, OrderRepository, CreateOrderCommandHandler, Controller | Must |
| 2       | UC-002 list orders | GetOrdersQueryHandler, GetOrdersQuery/Result, DTO | Must |
| 3       | UC-003 cancel order | Order.cancel(), CancelOrderCommandHandler, the OrderCancelled Event | Must |
| ...     | ...      | ...      | ...     |
```

#### 6.2 Establishing the project structure

Confirm the tech stack with the user. If it isn't decided yet, propose one based on the constraints:

- "What programming language and framework will you use?"
- "What database will you use? (RDB / NoSQL / a mix)"
- "If you need a message queue, which one? (Kafka, RabbitMQ, SQS, etc.)"

Once the tech stack is settled, create the package layout as domain-first + 4 layers. The conceptual structure looks like this (follow `architecture/directory-structure.md` and `implementations/<lang>/`'s implementation guide for the exact per-language file/directory names):

```
<domain>/
  domain/                          # the Domain layer (framework-independent)
    <aggregate-root>               # the Aggregate Root — encapsulates business rules, invariants
    <entity>                      # a child Entity
    <value-object>                # a Value Object (immutable)
    <domain-event>                # a Domain Event definition
    <aggregate>-repository        # the Repository interface
  application/
    adapter/
      <external-domain>-adapter   # an interface for calling an external domain
    service/
      <service-name>              # an interface for calling an external system (a Technical Service)
    command/
      <verb>-<noun>-command
    query/
      <verb>-<noun>-query
      <verb>-<noun>-result
    event/
      <domain-event>-handler      # a Domain Event Handler
  interface/
    <domain>-controller
    dto/
      <verb>-<noun>-request
      <verb>-<noun>-response
  infrastructure/
    <aggregate>-repository-impl       # the Repository implementation — ORM/DB access
    <external-domain>-adapter-impl    # an external-domain Adapter implementation
```

Put shared infrastructure (a DB connection/transaction manager, the Outbox, config, etc.) in a shared location separate from the domain modules — see `architecture/shared-modules.md` (in each `implementations/<lang>/docs/architecture/`) for the per-language convention.

Propose the package structure to the user, and once confirmed, create the directories and base files.

#### 6.3 Implementing per slice

For each slice (use case), implement the 4 layers in this order. Only move to the next slice once every layer of the current one is finished.

##### 6.3.1 Implementing the Domain layer

Generate code based on the Aggregate design docs:

- **Aggregate Root**: validate invariants in the constructor, apply business rules in state-changing methods, collect Domain Events.
- **Entity**: implement identifier and lifecycle-related logic.
- **Value Object**: implement as an immutable object. Implement an equality-comparison method.
- **Domain Event**: define an event data class. Include when it happened and an event ID.
- **Repository interface**: define it per Aggregate Root, in the domain layer.

Implementation principles:

- The Domain layer never depends on a framework, an ORM, or an external library.
- Every business rule and invariant is encapsulated inside a domain object.
- An Aggregate's internal state can never be changed directly from outside it.

##### 6.3.2 Implementing the Application layer

Generate code based on the Application Service spec:

- Implement an Application Service per use case.
- The Service only ever plays the coordinator role: look up the Aggregate from the Repository → call a domain method on the Aggregate → save it via the Repository → publish a Domain Event.
- Set the transaction boundary at the Application Service method level.
- Delegate business-rule validation to the Domain layer.

##### 6.3.3 Implementing the Infrastructure layer

- **The Repository implementation**: implements the domain interface. ORM mapping, defining the DB schema.
- **Event publishing**: implement message-queue integration. Event serialization/deserialization.
- **External-system adapters**: convert an external API response into the domain model, as an Anticorruption Layer.
- Write up the DB schema together, in this format:

```sql
-- the {Aggregate name} table
CREATE TABLE {table_name} (
  {column_name} {type} {constraints},
  ...
);
```

##### 6.3.4 Implementing the Interface layer

- Define REST API endpoints per use case.
- Define DTOs, and implement the DTO ↔ Command/Query mapping.
- Handle input validation (format, required fields).
- Write up the API endpoint list in this format:

```
| Method | Path | Description | Request Body | Response Body | Error codes |
|--------|------|------|-------------|--------------|----------|
| POST   | /api/orders | create an order | CreateOrderRequestBody | CreateOrderResponseBody | 400, 409 |
```

#### 6.4 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- The project structure and architecture guide (package composition, inter-layer dependency rules)
- An API spec (endpoints, request/response schemas, error codes)
- A DB schema definition
- An infrastructure-integration spec (message-queue topics, external API integration info)

---

## Agent 7: Validator

### Role

An agent that verifies whether the implemented code matches the domain model and requirements, and identifies improvements. **The test code was already written by the Test Engineer (Agent 5), so the Validator focuses on confirming the tests pass and on verifying code quality.**

**When verifying, also run through the self-review checklist in [checklist.md](checklist.md).**

### Input

- **Agent 1's deliverable**: the use-case list (including acceptance criteria)
- **Agent 3's deliverable**: the domain model per Aggregate, business rules/invariant specs, the Ubiquitous Language glossary
- **Agent 5's deliverable**: the test code
- **Agent 6's deliverable**: the implemented source code

### Output

- Test-execution results (whether everything passed, what failed)
- The domain-model verification checklist result
- A change log (what changed, why, and how)
- A list of updated deliverables (which deliverables were updated)

### Procedure

#### 7.1 Running tests and checking the result

Run every test the Test Engineer wrote and check the result.

- Confirm every test passes
- If any test fails, analyze the cause and ask the Implementer to fix it
- Confirm test coverage meets the acceptance criteria
- If a test case is missing, ask the Test Engineer to add it

#### 7.2 Domain-model verification

Verify whether the implemented code matches the domain-model deliverables, using this checklist:

- [ ] Was every Aggregate implemented as designed?
- [ ] Is every invariant reflected in code, inside the Aggregate Root?
- [ ] Is every Domain Event defined and published at the correct moment?
- [ ] Is the Ubiquitous Language reflected consistently in the code (class names, method names, variable names)?
- [ ] Do Aggregates reference each other only by ID, with no direct reference?
- [ ] Does the Domain layer avoid depending on infrastructure/a framework?
- [ ] Does the Application Service delegate to a domain object instead of carrying out business logic itself?

If a mismatch is found, report it to the user, and agree on whether fixing the code or the model is the right direction.

#### 7.3 Refactoring and re-adjusting boundaries

If any of these problems surface during verification, propose an improvement to the user:

- **An Aggregate is bloated**: propose the splitting criterion and the structure after splitting it.
- **A Bounded Context boundary is wrong**: explain the re-adjustment direction and its blast radius.
- **A terminology mismatch**: fix the mismatch between the code and the glossary, and update the glossary.
- **A missing business rule**: add a new rule discovered during testing.

If there's a change, also update whichever earlier-stage deliverable it affects.

#### 7.4 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- Test-execution results (whether everything passed, what failed and why)
- The domain-model verification checklist result
- A change log (what changed, why, and how)
- A list of updated deliverables (which deliverables were updated)

**Completion condition**: the process is complete once every test passes, the domain-model verification checklist is satisfied, and the user gives final confirmation.

---

## Agent 8: Legacy Analyzer

### Role

The very first agent run in the legacy-feature-fix workflow. Analyzes the current code for the feature being modified, traces its Vertical Slice path, and identifies the gap against the guide. Produces an analysis report so the downstream agents (DM, TD) can design against the right scope and current state.

### Input

- The user's feature-fix request (what feature, changed how)
- The current project's source code

### Output

- A legacy-code analysis report
  - A Vertical Slice path map (the list of files this feature goes through, and their layer mapping)
  - A guide-gap analysis table (the current code vs. the guide's standard, compliant/violating per item)
  - A list of external dependencies (other modules that call this Slice, other modules this Slice calls)
  - Refactoring-scope recommendations (files that should change, files that should not)

### Procedure

#### 8.1 Identifying the feature to fix

Get clear on the feature to fix from the user's request:

- "Which API endpoint (or use case) does the feature you want to fix correspond to?"
- "Do you know this feature's entry point (the Controller or the caller)?"

If the entry point is unclear, identify it via a code search and confirm with the user.

#### 8.2 Tracing the Vertical Slice path

Starting from the identified entry point, follow the call flow and trace every file this feature goes through:

1. **The Interface layer**: the Controller, DTOs (Request/Response)
2. **The Application layer**: the Service, Command/Query, Handler, Adapter interfaces
3. **The Domain layer**: the Aggregate Root, Entity, Value Object, Domain Event, the Repository interface
4. **The Infrastructure layer**: the Repository implementation, Adapter implementations, the ORM Entity/Schema

Write up the trace result in this format:

```
| Layer | File path | Role | Notes |
|--------|----------|------|------|
| Interface | src/order/interface/order.controller.ts | Controller | |
| Application | src/order/application/order.service.ts | Service | Command/Query not yet separated |
| Domain | src/order/domain/order.ts | Aggregate Root | |
| Infrastructure | src/order/infrastructure/order.repository-impl.ts | Repository implementation | |
```

#### 8.3 Analyzing the guide gap

Cross-check each file in the Slice against the guide docs to identify a violation. Check from these angles (map the STEP number against the actual project's `checklist.md` table of contents):

- **File structure and naming**: checklist.md STEP 1
- **The Domain layer**: checklist.md STEP 2 (where business logic lives, framework dependency, etc.)
- **Layer architecture**: checklist.md STEP 3 (the inter-layer dependency direction)
- **The Repository pattern**: checklist.md STEP 4
- **Error handling**: checklist.md's error-handling STEP

Write up the analysis result in this format:

```
| Item checked | Current state | The guide's standard | Verdict | Notes |
|-----------|----------|------------|------|------|
| kebab-case file names | order.service.ts | order-command-service.ts | Violation | needs CQRS separation |
| Domain has no framework dependency | uses a framework decorator/annotation | no framework dependency | Violation | |
| Business logic encapsulated in the Aggregate | validation logic is in the Service | should be inside the Aggregate | Violation | |
```

#### 8.4 Identifying external dependencies

Identify every relationship where another module calls this Slice's code, or this Slice calls another module:

- **Inbound**: files in an external module that import this Slice's Service, Repository, or domain objects
- **Outbound**: the Service, Repository, or domain objects of an external module that this Slice imports

```
| Direction | External module | What it references | How it's referenced | Impact on refactoring |
|------|----------|----------|----------|----------------|
| Inbound | payment | OrderService.findById() | direct call | the interface must be kept |
| Outbound | product | ProductService.getPrice() | direct call | consider switching to an Adapter |
```

#### 8.5 Refactoring-scope recommendations

Combining the analysis from 8.2-8.4, recommend the change scope per the Vertical Slice refactoring strategy's scope rules:

- **In scope for change**: a file on this feature fix's direct path, with a guide violation
- **Out of scope**: a file in the same module unrelated to this feature, or another module's shared code
- **Needs care**: an interface an external module depends on — its callers need to be updated at the same time as a change to it

```
| File | Recommendation | Reason |
|------|------|------|
| src/order/application/order.service.ts | change | needs CQRS separation + is the feature-fix target |
| src/order/domain/order.ts | change | business logic needs to move here |
| src/order/interface/order-admin.controller.ts | exclude | an API unrelated to this feature |
| src/payment/application/payment.service.ts | needs care | its call to OrderService needs updating |
```

#### 8.6 Writing the deliverable

Write up the following deliverables as a markdown doc and ask the user to confirm:

- The Vertical Slice path map
- The guide-gap analysis table
- The list of external dependencies
- The refactoring-scope recommendation table

**Completion condition**: once the user confirms the analysis report and agrees on the refactoring scope, hand the deliverable off to DM (Domain Modeler).
