import { Repository } from 'typeorm'

export class GetAccountQueryHandler {
  constructor(private readonly repo: Repository<unknown>) {}
}
