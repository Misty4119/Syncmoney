import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

class MockEventSource {
  static instances: MockEventSource[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn()
  addEventListener = vi.fn()
  constructor(public url: string) { MockEventSource.instances.push(this) }
}

describe('useSSE', () => {
  let sse: ReturnType<typeof import('./useSSE').useSSE>

  beforeEach(async () => {
    vi.useFakeTimers()
    vi.resetModules()
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ success: true, data: { token: 'test-session' } })
    }))
    localStorage.setItem('apiKey', 'test-key')
    sse = (await import('./useSSE')).useSSE()
  })

  afterEach(() => {
    sse.disconnect()
    localStorage.clear()
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  async function connect() {
    sse.connect('/sse')
    await vi.advanceTimersByTimeAsync(0)
    const source = MockEventSource.instances[0]!
    source.onopen?.()
    return source
  }

  it('starts disconnected', () => expect(sse.connected.value).toBe(false))

  it('obtains a session token before opening the stream', async () => {
    const source = await connect()
    expect(fetch).toHaveBeenCalledWith('/api/auth/ws-token', expect.objectContaining({ method: 'POST' }))
    expect(source.url).toBe('/sse?token=test-session')
    expect(sse.connected.value).toBe(true)
  })

  it('dispatches messages and unregisters handlers', async () => {
    const source = await connect()
    const handler = vi.fn()
    sse.on('system', handler)
    source.onmessage?.({ data: JSON.stringify({ type: 'system', data: { message: 'test' } }) })
    expect(handler).toHaveBeenCalledWith({ message: 'test' })
    sse.off('system', handler)
    source.onmessage?.({ data: JSON.stringify({ type: 'system', data: {} }) })
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('closes the stream on explicit disconnect', async () => {
    const source = await connect()
    sse.disconnect()
    expect(source.close).toHaveBeenCalledOnce()
    expect(sse.connected.value).toBe(false)
  })

  it('does not reopen after disconnect while token acquisition is pending', async () => {
    sse.connect('/sse')
    sse.disconnect()
    await vi.advanceTimersByTimeAsync(0)
    expect(MockEventSource.instances).toHaveLength(0)
  })
})
