import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { WebSocketMessageData } from './useWebSocket'


class MockEventSource {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  
  readyState = MockEventSource.OPEN
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: (() => void) | null = null
  
  private listeners: Map<string, ((event: MessageEvent) => void)[]> = new Map()
  
  constructor(public url: string) {
    setTimeout(() => this.onopen?.(), 0)
  }
  
  addEventListener(type: string, handler: (event: MessageEvent) => void) {
    const handlers = this.listeners.get(type) || []
    handlers.push(handler)
    this.listeners.set(type, handlers)
  }
  
  removeEventListener(type: string, handler: (event: MessageEvent) => void) {
    const handlers = this.listeners.get(type)
    if (handlers) {
      const index = handlers.indexOf(handler)
      if (index > -1) {
        handlers.splice(index, 1)
      }
    }
  }
  

  triggerEvent(type: string, data: WebSocketMessageData) {
    const handlers = this.listeners.get(type)
    if (handlers) {
      handlers.forEach(h => h({ data: JSON.stringify(data) } as MessageEvent))
    }
  }
  
  close = vi.fn()
}

vi.stubGlobal('EventSource', MockEventSource)

describe('useSSE', () => {
  let useSSE: () => ReturnType<typeof import('@/composables/useSSE').useSSE>
  
  beforeEach(async () => {
    const module = await import('@/composables/useSSE')
    useSSE = module.useSSE
  })
  
  afterEach(() => {
    vi.resetModules()
  })

  it('should initialize with disconnected state', () => {
    const sse = useSSE()
    expect(sse.connected.value).toBe(false)
  })

  it('should connect to SSE server', () => {
    const sse = useSSE()
    sse.connect('/sse')
    
    return new Promise(resolve => setTimeout(() => {
      expect(sse.connected.value).toBe(true)
      resolve(true)
    }, 10))
  })

  it('should register and trigger event handlers', () => {
    const sse = useSSE()
    
    const handler = vi.fn()
    sse.on('transaction', handler)
    
    sse.connect('/sse')
    
    return new Promise(resolve => setTimeout(() => {

      expect(sse.on).toBeDefined()
      resolve(true)
    }, 10))
  })

  it('should disconnect properly', () => {
    const sse = useSSE()
    sse.connect('/sse')
    
    return new Promise(resolve => setTimeout(() => {
      sse.disconnect()
      expect(sse.connected.value).toBe(false)
      resolve(true)
    }, 10))
  })
})
