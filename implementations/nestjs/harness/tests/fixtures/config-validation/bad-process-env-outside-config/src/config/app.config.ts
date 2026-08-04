export const appConfig = () => ({
  port: Number(process.env.PORT ?? 3000)
})

export const appConfigValidationSchema = {
  PORT: 'number'
}
