import { AccountErrorMessage as ErrorMessage } from '@/account/account-error-message'

export class Money {
  public readonly amount: number
  public readonly currency: string

  constructor(params: { amount: number; currency: string }) {
    if (params.amount < 0) throw new Error(ErrorMessage['The amount must be 0 or greater.'])
    this.amount = params.amount
    this.currency = params.currency
  }

  public add(other: Money): Money {
    this.assertSameCurrency(other)
    return new Money({ amount: this.amount + other.amount, currency: this.currency })
  }

  public subtract(other: Money): Money {
    this.assertSameCurrency(other)
    return new Money({ amount: this.amount - other.amount, currency: this.currency })
  }

  public isLessThan(other: Money): boolean {
    this.assertSameCurrency(other)
    return this.amount < other.amount
  }

  public isZero(): boolean {
    return this.amount === 0
  }

  public equals(other: Money): boolean {
    return this.amount === other.amount && this.currency === other.currency
  }

  private assertSameCurrency(other: Money): void {
    if (this.currency !== other.currency) throw new Error(ErrorMessage['The currencies do not match.'])
  }
}
