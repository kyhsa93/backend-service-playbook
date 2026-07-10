export function getDatabaseUrl(): string {
  return process.env.DATABASE_URL ?? ''
}
