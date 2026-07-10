import { Injectable, NestMiddleware } from '@nestjs/common'
import { Request, Response, NextFunction } from 'express'
import { randomUUID } from 'crypto'

import { CorrelationIdStore } from '@/common/correlation-id-store'

@Injectable()
export class CorrelationIdMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction) {
    const correlationId = (req.headers['x-correlation-id'] as string) ?? randomUUID()
    CorrelationIdStore.run(correlationId, () => {
      res.setHeader('x-correlation-id', correlationId)
      next()
    })
  }
}
