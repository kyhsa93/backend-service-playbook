import { TransactionAutoCategorizerImpl } from '@/account/infrastructure/transaction-auto-categorizer-impl'

describe('TransactionAutoCategorizerImpl', () => {
  let categorizer: TransactionAutoCategorizerImpl
  let fetchMock: jest.Mock

  beforeEach(() => {
    categorizer = new TransactionAutoCategorizerImpl()
    fetchMock = jest.fn()
    global.fetch = fetchMock as never
  })

  function mockOllamaResponse(content: unknown): void {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ message: { content: JSON.stringify(content) } })
    })
  }

  it('categorize_when_the_model_returns_a_valid_category_then_returns_it', async () => {
    mockOllamaResponse({ category: 'FOOD' })

    const category = await categorizer.categorize({ merchantName: 'Starbucks Gangnam', amount: 5500 })

    expect(category).toBe('FOOD')
  })

  it('categorize_when_the_model_returns_an_out-of-taxonomy_category_then_falls_back_to_OTHER', async () => {
    mockOllamaResponse({ category: 'NOT_A_REAL_CATEGORY' })

    const category = await categorizer.categorize({ merchantName: 'Unknown Payee', amount: 1000 })

    expect(category).toBe('OTHER')
  })

  it('categorize_when_the_ollama_call_fails_then_falls_back_to_OTHER_rather_than_throwing', async () => {
    fetchMock.mockRejectedValue(new Error('connection refused'))

    const category = await categorizer.categorize({ merchantName: 'Anything', amount: 1000 })

    expect(category).toBe('OTHER')
  })

  it('categorize_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_OTHER', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500 })

    const category = await categorizer.categorize({ merchantName: 'Anything', amount: 1000 })

    expect(category).toBe('OTHER')
  })
})
