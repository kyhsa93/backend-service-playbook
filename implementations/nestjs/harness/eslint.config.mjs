// @ts-check
import eslint from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  {
    // tests/fixtures/**는 harness 자체 테스트용 "의도적으로 잘못된" 코드 샘플이다.
    // lint 대상에서 제외한다 — 고쳐서는 안 된다.
    ignores: ['node_modules/**', 'tests/fixtures/**']
  },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: {
      sourceType: 'commonjs',
      globals: {
        ...globals.node
      }
    },
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }
      ]
    }
  }
)
