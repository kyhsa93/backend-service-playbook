import { Injectable } from '@nestjs/common'
import { Interval } from '@nestjs/schedule'

@Injectable()
export class CacheWarmerService {
  @Interval(60000)
  public warmCache(): void {
    // 인메모리 캐시 워밍 — Application 레이어에 잘못 위치한 예시
    console.log('warming cache')
  }
}
