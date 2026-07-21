import { Transaction } from '@/account/domain/transaction'

export class TransferResult {
  constructor(
    public readonly transferId: string,
    public readonly sourceTransaction: Transaction,
    public readonly targetTransaction: Transaction
  ) {}
}
