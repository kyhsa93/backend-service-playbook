import { CallHandler, ExecutionContext, Injectable, NestInterceptor } from '@nestjs/common'
import { Request, Response } from 'express'
import { Observable } from 'rxjs'
import { tap } from 'rxjs/operators'

import { httpRequestDurationSeconds, httpRequestsTotal } from '@/common/metrics-registry'

// Registered globally alongside LoggingInterceptor (main.ts) — a separate interceptor rather
// than folding this into LoggingInterceptor, since logging and metrics are two distinct
// cross-cutting concerns that happen to read the same (method, route, status, duration) facts
// off the same request/response but serve different consumers (structured logs vs. a
// Prometheus scrape). See docs/architecture/observability.md.
@Injectable()
export class MetricsInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const req = context.switchToHttp().getRequest<Request>()
    const { method } = req
    // req.route is only populated once Express has matched a route — by the time an
    // interceptor's intercept() runs, routing has already happened, so this is the route
    // pattern (e.g. '/accounts/:accountId'), not the raw URL. Keeps metric cardinality bounded
    // regardless of how many distinct IDs are requested. Falls back to the raw path for the
    // rare case a route never got matched (e.g. a 404 short-circuited before routing).
    const route = (req.route as { path?: string } | undefined)?.path ?? req.path
    const startedAt = process.hrtime.bigint()

    const record = (): void => {
      const res = context.switchToHttp().getResponse<Response>()
      const labels = { method, route, status_code: String(res.statusCode) }
      const durationSeconds = Number(process.hrtime.bigint() - startedAt) / 1e9
      httpRequestsTotal.inc(labels)
      httpRequestDurationSeconds.observe(labels, durationSeconds)
    }

    return next.handle().pipe(tap({ next: record, error: record }))
  }
}
