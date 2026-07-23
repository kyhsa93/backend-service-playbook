import { Injectable, NestMiddleware } from '@nestjs/common'
import { Request, Response, NextFunction } from 'express'
import { randomUUID } from 'crypto'
import { trace } from '@opentelemetry/api'

import { CorrelationIdStore } from '@/common/correlation-id-store'

@Injectable()
export class CorrelationIdMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction) {
    const correlationId = (req.headers['x-correlation-id'] as string) ?? randomUUID()
    // @opentelemetry/instrumentation-http (registered in src/tracing.ts) already started a span
    // for this request by the time Nest middleware runs, so its trace ID is available here —
    // folded into the same store as correlationId so every log downstream can include trace_id
    // with no separate lookup (see correlation-id-store.ts).
    const traceId = trace.getActiveSpan()?.spanContext().traceId
    CorrelationIdStore.run({ correlationId, traceId }, () => {
      res.setHeader('x-correlation-id', correlationId)
      next()
    })
  }
}
