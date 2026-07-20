import { User } from '@/user/domain/user'

export class Order {
  public readonly orderId: string
  public readonly user: User
}
