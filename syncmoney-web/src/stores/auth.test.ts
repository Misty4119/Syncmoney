import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { apiClient } from '@/api/client'

vi.mock('@/api/client', () => {
  const get = vi.fn()
  return { apiClient: { get, create: () => ({ get }) } }
})


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
    vi.resetAllMocks()
    localStorageMock.clear()
    vi.mocked(apiClient.get).mockResolvedValue({ data: { success: true } })
  })

  it('should initialize with empty state', () => {
    const store = useAuthStore()
    expect(store.apiKey).toBe('')
    expect(store.isAuthenticated).toBe(false)
  })

  it('should check authentication from localStorage', async () => {
    ;(localStorage.getItem as ReturnType<typeof vi.fn>).mockReturnValue('test-api-key')
    
    const store = useAuthStore()
    await store.checkAuth()
    
    expect(store.apiKey).toBe('test-api-key')
    expect(store.isAuthenticated).toBe(true)
    expect(apiClient.get).toHaveBeenCalledWith('/api/system/status', {
      headers: { Authorization: 'Bearer test-api-key' }
    })
  })

  it('should clear a rejected stored token', async () => {
    vi.mocked(localStorage.getItem).mockReturnValue('rejected-key')
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Unauthorized'))
    const store = useAuthStore()
    await store.checkAuth()
    expect(store.isAuthenticated).toBe(false)
    expect(store.apiKey).toBe('')
  })

  it('should logout and clear storage', () => {
    const store = useAuthStore()
    store.logout()
    
    expect(store.apiKey).toBe('')
    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.removeItem).toHaveBeenCalledWith('apiKey')
  })
})
