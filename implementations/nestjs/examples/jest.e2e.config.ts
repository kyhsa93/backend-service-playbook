export default {
  moduleFileExtensions: ['js', 'json', 'ts'],
  rootDir: '.',
  testRegex: '.*\\.e2e-spec\\.ts$',
  transform: { '^.+\\.(t|j)s$': 'ts-jest' },
  testEnvironment: 'node',
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/src/$1' },
  testTimeout: 120000,
  // Same pin as jest.config.ts — see test/jest-global-setup.ts. An E2E test boots the real
  // AppModule in-process without going through main.ts, so without this the suite would write
  // timestamps in the developer's local zone while the deployed service writes UTC.
  globalSetup: '<rootDir>/test/jest-global-setup.ts'
}
