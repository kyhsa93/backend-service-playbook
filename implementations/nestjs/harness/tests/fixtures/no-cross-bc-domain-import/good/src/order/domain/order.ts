// user-enum.ts는 user BC의 domain/ 밖(BC 루트)이고, generate-id.ts는 domain/이
// 없는 common 모듈이므로 둘 다 이 규칙의 대상이 아니다(오탐 방지 회귀).
import { UserStatus } from '@/user/user-enum'
import { generateId } from '@/common/generate-id'

export class Order {
  public readonly orderId: string = generateId()
  public readonly userId: string
  public readonly userStatus: UserStatus
}
