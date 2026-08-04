import { Repository } from 'typeorm'

export class CreateAccountHandler {
  constructor(private readonly repository: Repository<unknown>) {}
}
