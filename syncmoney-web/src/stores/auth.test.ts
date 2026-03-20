import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'


const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] || null),
    setItem: vi.fn((key: string, value: string) => { store[key] = value }),
    removeItem: vi.fn((key: string) => { delete store[key] }),
    clear: vi.fn(() => { store = {} })
  }
})()

Object.defineProperty(window, 'localStorage', { value: localStorageMock })

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should initialize with empty state', () => {
    const store = useAuthStore()
    expect(store.apiKey).toBe('')
    expect(store.isAuthenticated).toBe(false)
  })

  it('should check authentication from localStorage', () => {
    ;(localStorage.getItem as ReturnType<typeof vi.fn>).mockReturnValue('test-api-key')
    
    const store = useAuthStore()
    store.checkAuth()
    
    expect(store.apiKey).toBe('test-api-key')
    expect(store.isAuthenticated).toBe(true)
  })

  it('should logout and clear storage', () => {
    const store = useAuthStore()
    store.logout()
    
    expect(store.apiKey).toBe('')
    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.removeItem).toHaveBeenCalledWith('apiKey')
  })
})
