import { http, HttpResponse } from 'msw'

// Simulated plugin config data with editable/readonly fields
const mockConfig = {
  core: {
    'server-name': { value: 'MainServer', type: 'string', editable: false },
    'queue-capacity': { value: 512, type: 'number', editable: false, min: 64, max: 4096 },
    'sync-interval': { value: 5000, type: 'number', editable: true, min: 1000, max: 30000 },
    'debug-mode': { value: false, type: 'boolean', editable: true },
    'log-level': { value: 'INFO', type: 'string', editable: true, allowedValues: ['DEBUG', 'INFO', 'WARN', 'ERROR'] },
  },
  economy: {
    'currency-name': { value: 'Coins', type: 'string', editable: true },
    'currency-symbol': { value: '$', type: 'string', editable: true },
    'default-balance': { value: 1000, type: 'number', editable: true, min: 0, max: 100000 },
    'max-balance': { value: 1000000, type: 'number', editable: true, min: 1000 },
    'decimal-places': { value: 2, type: 'number', editable: false, min: 0, max: 8 },
    'allow-negative': { value: false, type: 'boolean', editable: true },
  },
  redis: {
    enabled: true,
    host: 'localhost',
    port: '6379',
    password: '',
    editable: true,
    'connection-timeout': { value: 5000, type: 'number', editable: true, min: 1000, max: 30000 },
    'max-connections': { value: 16, type: 'number', editable: true, min: 1, max: 128 },
  },
  database: {
    type: 'SQLite',
    host: 'localhost',
    port: '3306',
    database: 'syncmoney',
    editable: false,
    'pool-size': { value: 10, type: 'number', editable: true, min: 1, max: 50 },
  },
  'circuit-breaker': {
    enabled: { value: true, type: 'boolean', editable: true },
    'failure-threshold': { value: 5, type: 'number', editable: true, min: 1, max: 20 },
    'reset-timeout': { value: 30000, type: 'number', editable: true, min: 5000, max: 300000 },
    'half-open-requests': { value: 3, type: 'number', editable: true, min: 1, max: 10 },
  },
  display: {
    'show-prefix': { value: true, type: 'boolean', editable: true },
    'prefix': { value: '&6[SM]&r ', type: 'string', editable: true },
    'date-format': { value: 'yyyy-MM-dd HH:mm:ss', type: 'string', editable: true },
    'thousands-separator': { value: ',', type: 'string', editable: true },
    'decimal-separator': { value: '.', type: 'string', editable: true },
  },
  pay: {
    enabled: { value: true, type: 'boolean', editable: true },
    'minimum-amount': { value: 0.01, type: 'number', editable: true, min: 0.001 },
    'maximum-amount': { value: 100000, type: 'number', editable: true },
    'cooldown': { value: 0, type: 'number', editable: true, min: 0, max: 3600 },
    'self-pay': { value: false, type: 'boolean', editable: true },
    'confirm-payment': { value: true, type: 'boolean', editable: true },
  },
  baltop: {
    enabled: { value: true, type: 'boolean', editable: true },
    'entries-per-page': { value: 10, type: 'number', editable: true, min: 5, max: 50 },
    'cache-duration': { value: 60, type: 'number', editable: true, min: 10, max: 3600 },
    'exclude-operators': { value: false, type: 'boolean', editable: true },
  },
  audit: {
    enabled: { value: true, type: 'boolean', editable: true },
    'max-entries': { value: 10000, type: 'number', editable: false, min: 100 },
    'retention-days': { value: 30, type: 'number', editable: true, min: 1, max: 365 },
    'log-reads': { value: false, type: 'boolean', editable: true },
    'compress-old-logs': { value: true, type: 'boolean', editable: true },
  },
  'discord-webhook': {
    enabled: { value: false, type: 'boolean', editable: true },
    'webhook-url': { value: '', type: 'string', editable: true },
    'notify-transactions': { value: true, type: 'boolean', editable: true },
    'notify-threshold': { value: 1000, type: 'number', editable: true, min: 0 },
    'notify-breaker': { value: true, type: 'boolean', editable: true },
  },
  'admin-permissions': {
    'use-op-fallback': { value: true, type: 'boolean', editable: false },
    'admin-permission': { value: 'syncmoney.admin', type: 'string', editable: false },
    'allow-console': { value: true, type: 'boolean', editable: false },
  },
  'cross-server-notifications': {
    enabled: { value: false, type: 'boolean', editable: true },
    'notify-join': { value: true, type: 'boolean', editable: true },
    'notify-quit': { value: true, type: 'boolean', editable: true },
    'notify-pay': { value: true, type: 'boolean', editable: true },
  },
  'shadow-sync': {
    enabled: { value: false, type: 'boolean', editable: true },
    'sync-target': { value: '', type: 'string', editable: true },
    'sync-interval': { value: 10000, type: 'number', editable: true, min: 1000 },
  },
  'web-admin.ui': {
    'items-per-page': { value: 20, type: 'number', editable: true, min: 10, max: 100 },
    'theme': { value: 'dark', type: 'string', editable: true, allowedValues: ['light', 'dark', 'system'] },
    'session-timeout': { value: 3600, type: 'number', editable: false, min: 300, max: 86400 },
  },
  messages: {
    'language': { value: 'en', type: 'string', editable: false, allowedValues: ['en', 'zh-TW', 'zh-CN', 'ja', 'ko'] },
    'use-miniMessage': { value: false, type: 'boolean', editable: true },
  }
}

export const configHandlers = [

  // GET /api/config — returns full plugin configuration
  http.get('/api/config', () => {
    return HttpResponse.json({
      success: true,
      data: mockConfig
    })
  }),

  // PUT /api/config — saves configuration changes
  http.put('/api/config', async ({ request }) => {
    const body = await request.json() as { changes: Array<{ section: string; key: string; value: unknown }>, hotReload: boolean }
    // Apply changes to the mock config
    for (const change of body.changes ?? []) {
      const section = mockConfig[change.section as keyof typeof mockConfig] as Record<string, unknown>
      if (section) {
        const field = section[change.key] as Record<string, unknown>
        if (field && typeof field === 'object' && 'value' in field) {
          field.value = change.value
        } else {
          section[change.key] = change.value
        }
      }
    }
    return HttpResponse.json({
      success: true,
      data: {
        message: 'Configuration saved successfully',
        hotReloaded: body.hotReload ?? true
      }
    })
  }),

  // POST /api/config/validate — validates a config value
  http.post('/api/config/validate', async ({ request }) => {
    const body = await request.json() as { section: string; key: string; value: unknown }
    // Simple validation: always valid for mock
    return HttpResponse.json({
      success: true,
      data: {
        valid: true,
        message: `Value for ${body.section}.${body.key} is valid`
      }
    })
  }),

  // POST /api/config/reload — triggers config hot reload
  http.post('/api/config/reload', () => {
    return HttpResponse.json({
      success: true,
      data: { message: 'Configuration reloaded successfully' }
    })
  }),
]
