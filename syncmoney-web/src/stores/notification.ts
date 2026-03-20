import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import i18n from '@/i18n'

export type NotificationType = 'info' | 'success' | 'warning' | 'error'
export type NotificationCategory = 'system' | 'security' | 'transaction' | 'audit' | 'general'

export interface Notification {
  id: string
  type: NotificationType
  title: string
  message: string
  timestamp: number
  category?: NotificationCategory

  persistent?: boolean
  read?: boolean
}

const STORAGE_KEY = 'syncmoney_alerts'
const MAX_PERSISTENT_ALERTS = 50

function generateUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0
    const v = c === 'x' ? r : (r & 0x3 | 0x8)
    return v.toString(16)
  })
}

function loadAlertsFromStorage(): Notification[] {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored ? JSON.parse(stored) : []
  } catch {
    return []
  }
}

function saveAlertsToStorage(alerts: Notification[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(alerts))
  } catch {
    console.warn('Failed to save alerts to localStorage')
  }
}

export const useNotificationStore = defineStore('notification', () => {

  const toasts = ref<Notification[]>([])
  const toastsEnabled = ref(true)


  const alerts = ref<Notification[]>(loadAlertsFromStorage())


  const unreadAlertCount = computed(() => alerts.value.filter(a => !a.read).length)



  function addToast(type: NotificationType, title: string, message: string, category?: NotificationCategory) {
    if (!toastsEnabled.value) return

    const toast: Notification = {
      id: generateUUID(),
      type,
      title,
      message,
      timestamp: Date.now(),
      category,
      persistent: false
    }

    toasts.value.unshift(toast)


    setTimeout(() => {
      removeToast(toast.id)
    }, 5000)
  }

  function removeToast(id: string) {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }

  function clearToasts() {
    toasts.value = []
  }



  function addAlert(type: NotificationType, title: string, message: string, category?: NotificationCategory) {
    const alert: Notification = {
      id: generateUUID(),
      type,
      title,
      message,
      timestamp: Date.now(),
      category,
      persistent: true,
      read: false
    }

    alerts.value.unshift(alert)


    if (alerts.value.length > MAX_PERSISTENT_ALERTS) {
      alerts.value = alerts.value.slice(0, MAX_PERSISTENT_ALERTS)
    }


    saveAlertsToStorage(alerts.value)
  }

  function markAlertAsRead(id: string) {
    const alert = alerts.value.find(a => a.id === id)
    if (alert) {
      alert.read = true
      saveAlertsToStorage(alerts.value)
    }
  }

  function markAllAlertsAsRead() {
    alerts.value.forEach(a => a.read = true)
    saveAlertsToStorage(alerts.value)
  }

  function removeAlert(id: string) {
    alerts.value = alerts.value.filter(a => a.id !== id)
    saveAlertsToStorage(alerts.value)
  }

  function clearAlerts() {
    alerts.value = []
    saveAlertsToStorage([])
  }



  /**
   * @deprecated Use addToast() or addAlert() instead for better separation
   */
  function addNotification(type: NotificationType, title: string, message: string, eventType?: string) {

    addToast(type, title, message, eventType as NotificationCategory)
  }

  function addTransactionNotification(playerName: string, amount: string, type: string, title?: string) {
    const notificationType: NotificationType = type === 'WITHDRAW' || type === 'SET_BALANCE' ? 'warning' : 'info'
    const amountPrefix = type === 'DEPOSIT' ? '+' : type === 'WITHDRAW' ? '-' : ''

    if (title && title.includes(':')) {
      addToast(notificationType, title, '', 'transaction')
    } else {
      addToast(
        notificationType,
        title || i18n.global.t('notification.newTransaction'),
        `${playerName}: ${amountPrefix}${amount}`,
        'transaction'
      )
    }
  }

  function addBreakerNotification(state: string, title?: string) {

    const toastTitle = title || i18n.global.t('notification.circuitBreakerAlert')
    const message = `State changed to: ${state}`


    addToast('warning', toastTitle, message, 'security')


    addAlert('warning', toastTitle, message, 'security')
  }

  function addSystemAlertNotification(message: string) {

    const title = i18n.global.t('notification.systemAlert')


    addToast('error', title, message, 'system')


    addAlert('error', title, message, 'system')
  }

  function addSecurityAlert(title: string, message: string) {

    addAlert('error', title, message, 'security')
  }

  function addNewVersionAlert(version: string) {
    const title = i18n.global.t('notification.newVersionAvailable') || 'New Version Available'
    const message = `Syncmoney v${version} is now available`
    addAlert('info', title, message, 'system')
  }

  function success(title: string, message: string) {
    addToast('success', title, message)
  }

  function error(title: string, message: string) {
    addToast('error', title, message)
  }

  function info(title: string, message: string) {
    addToast('info', title, message)
  }

  function warning(title: string, message: string) {
    addToast('warning', title, message)
  }

  function setToastsEnabled(value: boolean) {
    toastsEnabled.value = value
    localStorage.setItem('toastsEnabled', String(value))
  }

  function resetStore() {
    toasts.value = []
    alerts.value = []
    saveAlertsToStorage([])
  }


  toastsEnabled.value = localStorage.getItem('toastsEnabled') !== 'false'

  return {

    toasts,
    toastsEnabled,


    alerts,
    unreadAlertCount,


    addToast,
    removeToast,
    clearToasts,
    setToastsEnabled,


    addAlert,
    markAlertAsRead,
    markAllAlertsAsRead,
    removeAlert,
    clearAlerts,
    resetStore,


    addNotification,
    addTransactionNotification,
    addBreakerNotification,
    addSystemAlertNotification,
    addSecurityAlert,
    addNewVersionAlert,
    success,
    error,
    info,
    warning
  }
})
