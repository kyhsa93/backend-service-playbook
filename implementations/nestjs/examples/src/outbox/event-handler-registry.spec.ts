import { EventHandlerRegistry } from '@/outbox/event-handler-registry'

describe('EventHandlerRegistry', () => {
  let registry: EventHandlerRegistry

  beforeEach(() => {
    registry = new EventHandlerRegistry()
  })

  it('handle_when_multiple_handlers_are_registered_for_the_same_eventType_then_calls_all_of_them', async () => {
    const first = jest.fn().mockResolvedValue(undefined)
    const second = jest.fn().mockResolvedValue(undefined)
    registry.register('SomeEvent', first)
    registry.register('SomeEvent', second)

    await registry.handle('SomeEvent', { some: 'payload' })

    expect(first).toHaveBeenCalledWith({ some: 'payload' })
    expect(second).toHaveBeenCalledWith({ some: 'payload' })
  })

  it('handle_when_one_handler_throws_then_still_calls_the_other_handler_rather_than_stopping_early', async () => {
    const failing = jest.fn().mockRejectedValue(new Error('boom'))
    const succeeding = jest.fn().mockResolvedValue(undefined)
    registry.register('SomeEvent', failing)
    registry.register('SomeEvent', succeeding)

    await expect(registry.handle('SomeEvent', {})).rejects.toThrow('boom')

    expect(failing).toHaveBeenCalled()
    expect(succeeding).toHaveBeenCalled()
  })

  it('handle_when_a_handler_throws_then_rethrows_so_the_caller_can_leave_the_message_unacked', async () => {
    registry.register('SomeEvent', jest.fn().mockRejectedValue(new Error('boom')))

    await expect(registry.handle('SomeEvent', {})).rejects.toThrow('boom')
  })

  it('handle_when_no_handler_is_registered_for_the_eventType_then_resolves_without_error', async () => {
    await expect(registry.handle('UnregisteredEvent', {})).resolves.toBeUndefined()
  })
})
