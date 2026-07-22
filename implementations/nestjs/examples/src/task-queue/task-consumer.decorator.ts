// A decorator binding a taskType to a Task Controller method. Serves the same purpose
// (positional marker + runtime lookup) as outbox/event-handler-registry.ts's @HandleEvent, but
// while a Domain Event is 1:N fan-out routed via an explicit register() call, a Task allows
// exactly one handler per taskType — 1:1 routing that needs a global-uniqueness check — so it
// uses a decorator + global Map combination that validates immediately at registration time
// (when the module loads and this file is imported, evaluating the class body)
// (see docs/architecture/scheduling.md, the @TaskConsumer Decorator section).
const TASK_HANDLER_MAP = new Map<string, { handlerClass: new (...args: unknown[]) => unknown; method: string }>()

export function TaskConsumer(taskType: string): MethodDecorator {
  return (target, propertyKey) => {
    if (TASK_HANDLER_MAP.has(taskType)) {
      throw new Error(`Duplicate @TaskConsumer for taskType: ${taskType}`)
    }
    TASK_HANDLER_MAP.set(taskType, {
      handlerClass: target.constructor as new (...args: unknown[]) => unknown,
      method: propertyKey as string
    })
  }
}

export function getTaskHandler(taskType: string): { handlerClass: new (...args: unknown[]) => unknown; method: string } | undefined {
  return TASK_HANDLER_MAP.get(taskType)
}
