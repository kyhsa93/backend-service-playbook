import { Injectable } from '@nestjs/common'
import { Interval } from '@nestjs/schedule'

@Injectable()
export class CacheWarmerService {
  @Interval(60000)
  public warmCache(): void {
    // in-memory cache warming — an example incorrectly placed in the Application layer
    console.log('warming cache')
  }
}
