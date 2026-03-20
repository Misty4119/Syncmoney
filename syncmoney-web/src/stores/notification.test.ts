import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useNotificationStore } from './notification'

describe('Notification Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const store = useNotificationStore()
    store.resetStore()
    localStorage.clear()
  })

  it('should add toast notification', () => {
    const store = useNotificationStore()
    store.addToast('info', 'Test Title', 'Test Message')

    expect(store.toasts.length).toBe(1)
    expect(store.toasts[0].title).toBe('Test Title')
    expect(store.toasts[0].message).toBe('Test Message')
    expect(store.toasts[0].type).toBe('info')
  })

  it('should add multiple toast notifications', () => {
    const store = useNotificationStore()
    for (let i = 0; i < 15; i++) {
      store.addToast('info', `Title ${i}`, `Message ${i}`)
    }

    expect(store.toasts.length).toBe(15)
  })

  it('should remove toast notification', () => {
    const store = useNotificationStore()
    store.addToast('info', 'Test', 'Message')
    expect(store.toasts.length).toBe(1)

    store.removeToast(store.toasts[0].id)
    expect(store.toasts.length).toBe(0)
  })

  it('should clear all toasts', () => {
    const store = useNotificationStore()
    store.addToast('info', 'Test 1', 'Message 1')
    store.addToast('success', 'Test 2', 'Message 2')
    expect(store.toasts.length).toBe(2)

    store.clearToasts()
    expect(store.toasts.length).toBe(0)
  })

  it('should add alert notification (persistent)', () => {
    const store = useNotificationStore()
    store.addAlert('info', 'Alert Title', 'Alert Message')

    expect(store.alerts.length).toBe(1)
    expect(store.alerts[0].title).toBe('Alert Title')
    expect(store.alerts[0].persistent).toBe(true)
    expect(store.alerts[0].read).toBe(false)
  })

  it('should mark alert as read', () => {
    const store = useNotificationStore()
    store.addAlert('info', 'Test', 'Message')
    const alertId = store.alerts[0].id

    expect(store.alerts[0].read).toBe(false)
    store.markAlertAsRead(alertId)
    expect(store.alerts[0].read).toBe(true)
  })

  it('should remove alert notification', () => {
    const store = useNotificationStore()
    store.addAlert('info', 'Test', 'Message')
    const alertId = store.alerts[0].id

    store.removeAlert(alertId)
    expect(store.alerts.length).toBe(0)
  })

  it('should clear all alerts', () => {
    const store = useNotificationStore()
    store.addAlert('info', 'Test 1', 'Message 1')
    store.addAlert('success', 'Test 2', 'Message 2')

    store.clearAlerts()
    expect(store.alerts.length).toBe(0)
  })

  it('should calculate unread alert count', () => {
    const store = useNotificationStore()
    store.addAlert('info', 'Test 1', 'Message 1')
    store.addAlert('info', 'Test 2', 'Message 2')
    store.addAlert('info', 'Test 3', 'Message 3')

    expect(store.unreadAlertCount).toBe(3)

    store.markAlertAsRead(store.alerts[0].id)
    expect(store.unreadAlertCount).toBe(2)
  })

  it('should use convenience methods', () => {
    const store = useNotificationStore()

    store.success('Success', 'Message')
    store.error('Error', 'Message')
    store.info('Info', 'Message')
    store.warning('Warning', 'Message')

    expect(store.toasts.length).toBe(4)
    expect(store.toasts[0].type).toBe('warning')
    expect(store.toasts[1].type).toBe('info')
    expect(store.toasts[2].type).toBe('error')
    expect(store.toasts[3].type).toBe('success')
  })

  it('should add circuit breaker notification as both toast and alert', () => {
    const store = useNotificationStore()

    store.addBreakerNotification('OPEN', 'Circuit Breaker Alert')


    expect(store.toasts.length).toBe(1)
    expect(store.toasts[0].type).toBe('warning')


    expect(store.alerts.length).toBe(1)
    expect(store.alerts[0].type).toBe('warning')
    expect(store.alerts[0].category).toBe('security')
  })

  it('should add system alert notification as both toast and alert', () => {
    const store = useNotificationStore()

    store.addSystemAlertNotification('Database connection failed')


    expect(store.toasts.length).toBe(1)
    expect(store.toasts[0].type).toBe('error')


    expect(store.alerts.length).toBe(1)
    expect(store.alerts[0].type).toBe('error')
    expect(store.alerts[0].category).toBe('system')
  })
})
