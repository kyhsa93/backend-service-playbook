import { Logger } from '@nestjs/common'
import { plainToInstance } from 'class-transformer'
import { IsNotEmpty, IsString, validateSync } from 'class-validator'

class EnvironmentVariables {
  @IsString()
  @IsNotEmpty()
  DATABASE_URL: string
}

export function validateConfig(config: Record<string, unknown>): EnvironmentVariables {
  const validated = plainToInstance(EnvironmentVariables, config, {
    enableImplicitConversion: true
  })

  const errors = validateSync(validated, { skipMissingProperties: false })

  if (errors.length > 0) {
    Logger.error('Environment validation failed:', undefined, 'ConfigValidation')
    Logger.error(errors.map((e) => Object.values(e.constraints ?? {}).join(', ')).join('\n'), undefined, 'ConfigValidation')
    process.exit(1)
  }

  return validated
}
