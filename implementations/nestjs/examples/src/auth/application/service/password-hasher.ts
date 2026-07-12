export abstract class PasswordHasher {
  public abstract hash(plainPassword: string): Promise<string>
  public abstract verify(plainPassword: string, passwordHash: string): Promise<boolean>
}
