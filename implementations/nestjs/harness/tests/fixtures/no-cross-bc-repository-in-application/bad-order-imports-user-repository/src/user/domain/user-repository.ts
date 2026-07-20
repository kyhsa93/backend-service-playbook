export abstract class UserRepository {
  abstract findUsers(): Promise<void>
}
