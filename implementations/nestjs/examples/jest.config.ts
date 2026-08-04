export default {
  moduleFileExtensions: ['js', 'json', 'ts'],
  rootDir: '.',
  testRegex: '.*\\.spec\\.ts$',
  transform: { '^.+\\.(t|j)s$': 'ts-jest' },
  collectCoverageFrom: ['src/**/*.(t|j)s', '!src/**/*.entity.ts', '!src/**/*-module.ts'],
  coverageDirectory: './coverage',
  testEnvironment: 'node',
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/src/$1' },
  // Pins the run to UTC — see test/jest-global-setup.ts (it must be globalSetup, not
  // setupFiles: the latter runs against Jest's sandboxed copy of process.env and never reaches
  // the runtime's timezone).
  globalSetup: '<rootDir>/test/jest-global-setup.ts'
}
