import { RefundReasonClassifierImpl } from '@/payment/infrastructure/refund-reason-classifier-impl'

describe('RefundReasonClassifierImpl', () => {
  let classifier: RefundReasonClassifierImpl
  let fetchMock: jest.Mock

  beforeEach(() => {
    classifier = new RefundReasonClassifierImpl()
    fetchMock = jest.fn()
    global.fetch = fetchMock as never
  })

  function mockOllamaResponse(content: unknown): void {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ message: { content: JSON.stringify(content) } })
    })
  }

  it('classify_when_the_model_returns_a_valid_category_then_returns_it', async () => {
    mockOllamaResponse({ category: 'DEFECTIVE_PRODUCT' })

    const category = await classifier.classify('The item arrived broken')

    expect(category).toBe('DEFECTIVE_PRODUCT')
  })

  it('classify_when_the_model_returns_an_out-of-taxonomy_category_then_falls_back_to_OTHER', async () => {
    mockOllamaResponse({ category: 'NOT_A_REAL_CATEGORY' })

    const category = await classifier.classify('Some reason')

    expect(category).toBe('OTHER')
  })

  it('classify_when_the_ollama_call_fails_then_falls_back_to_OTHER_rather_than_throwing', async () => {
    fetchMock.mockRejectedValue(new Error('connection refused'))

    const category = await classifier.classify('Some reason')

    expect(category).toBe('OTHER')
  })

  it('classify_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_OTHER', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500 })

    const category = await classifier.classify('Some reason')

    expect(category).toBe('OTHER')
  })
})
