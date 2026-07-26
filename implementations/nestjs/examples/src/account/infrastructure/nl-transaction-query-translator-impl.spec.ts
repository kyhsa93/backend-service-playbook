import { NlTransactionQueryTranslatorImpl } from '@/account/infrastructure/nl-transaction-query-translator-impl'

describe('NlTransactionQueryTranslatorImpl', () => {
  let translator: NlTransactionQueryTranslatorImpl
  let fetchMock: jest.Mock

  beforeEach(() => {
    translator = new NlTransactionQueryTranslatorImpl()
    fetchMock = jest.fn()
    global.fetch = fetchMock as never
  })

  function mockOllamaResponse(content: unknown): void {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ message: { content: JSON.stringify(content) } })
    })
  }

  it('translate_when_the_model_returns_a_valid_type_and_dates_then_returns_them_as_the_filter', async () => {
    mockOllamaResponse({ type: 'WITHDRAWAL', fromDate: '2026-07-01', toDate: '2026-07-31' })

    const filter = await translator.translate('How much did I withdraw in July?')

    expect(filter).toEqual({ type: 'WITHDRAWAL', fromDate: '2026-07-01', toDate: '2026-07-31' })
  })

  it('translate_when_the_model_returns_an_invalid_type_then_drops_it_rather_than_passing_it_through', async () => {
    mockOllamaResponse({ type: 'NOT_A_REAL_TYPE', fromDate: '', toDate: '' })

    const filter = await translator.translate('anything')

    expect(filter.type).toBeUndefined()
  })

  it('translate_when_the_model_returns_a_malformed_date_then_drops_it_rather_than_passing_it_through', async () => {
    mockOllamaResponse({ type: 'ANY', fromDate: 'not-a-date', toDate: '2026-13-99' })

    const filter = await translator.translate('anything')

    expect(filter.fromDate).toBeUndefined()
    expect(filter.toDate).toBeUndefined()
  })

  it('translate_when_the_ollama_call_fails_then_falls_back_to_no_filter_rather_than_throwing', async () => {
    fetchMock.mockRejectedValue(new Error('connection refused'))

    const filter = await translator.translate('anything')

    expect(filter).toEqual({})
  })

  it('translate_when_ollama_responds_with_a_non_ok_status_then_falls_back_to_no_filter', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500 })

    const filter = await translator.translate('anything')

    expect(filter).toEqual({})
  })
})
