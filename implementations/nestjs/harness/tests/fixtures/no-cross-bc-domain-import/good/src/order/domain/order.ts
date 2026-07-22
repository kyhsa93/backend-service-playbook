// user-enum.ts is outside user BC's domain/ (the BC root), and generate-id.ts is a common
// module with no domain/, so neither is a target of this rule (a false-positive-prevention regression test).
import { UserStatus } from '@/user/user-enum'
import { generateId } from '@/common/generate-id'

export class Order {
  public readonly orderId: string = generateId()
  public readonly userId: string
  public readonly userStatus: UserStatus
}
