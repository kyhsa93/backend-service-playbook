export function getPort(): number {
  return parseInt(process.env.PORT ?? '3000', 10)
}

export function isProduction(): boolean {
  return process.env.NODE_ENV === 'production'
}
