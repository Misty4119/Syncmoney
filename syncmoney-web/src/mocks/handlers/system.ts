import { http, HttpResponse } from 'msw'

export const systemHandlers = [

  http.get('/api/system/status', () => {
    return HttpResponse.json({
      success: true,
      data: {
        plugin: {
          name: 'Syncmoney',
          version: '1.1.2',
          mode: 'Local',
          uptime: Date.now() - 3600000,
          onlinePlayers: 5,
          maxPlayers: 100
        },
        economy: {
          mode: 'Local',
          currencyName: 'Coins'
        },
        redis: {
          connected: true
        },
        database: {
          type: 'SQLite',
          connected: true
        },
        circuitBreaker: {
          state: 'NORMAL',
          lastTrigger: null
        }
      }
    })
  }),


  http.get('/api/system/metrics', () => {
    return HttpResponse.json({
      success: true,
      data: {
        economy: {
          transactionsToday: 156,
          transactionsYesterday: 203,
          averageTransactions: 180
        }
      }
    })
  }),


  http.get('/api/system/redis', () => {
    return HttpResponse.json({
      success: true,
      data: {
        connected: true,
        usedMemory: 1048576,
        uptime: 86400
      }
    })
  }),


  http.get('/api/system/breaker', () => {
    return HttpResponse.json({
      success: true,
      data: {
        state: 'NORMAL',
        lastTrigger: null,
        statistics: {
          totalTransactions: 15000,
          blockedTransactions: 5
        }
      }
    })
  })
]
