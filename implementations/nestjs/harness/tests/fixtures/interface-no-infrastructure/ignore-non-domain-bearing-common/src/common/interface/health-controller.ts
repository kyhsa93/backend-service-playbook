import { ShutdownState } from '@/common/infrastructure/shutdown-state'

// common/ is a cross-cutting-concern technical module with no domain/ layer, so it isn't a
// target of this rule (an intentional pattern documented in graceful-shutdown.md).
export class HealthController {
  constructor(private readonly shutdownState: ShutdownState) {}
}
