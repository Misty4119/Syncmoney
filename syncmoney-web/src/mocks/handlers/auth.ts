import { http, HttpResponse } from 'msw'

export const authHandlers = [

  http.get('/api/system/status', ({ request }) => {
    const authHeader = request.headers.get('Authorization')
    if (authHeader && authHeader.startsWith('Bearer ')) {
      return HttpResponse.json({
        success: true,
        data: {
          plugin: {
            name: 'Syncmoney',
            version: '1.1.0',
            mode: 'Local',
            uptime: 3600000,
            onlinePlayers: 5,
            maxPlayers: 100
          },
          redis: { connected: true },
          database: { type: 'SQLite', connected: true },
          circuitBreaker: { state: 'NORMAL' }
        }
      })
    }
    return HttpResponse.json(
      { success: false, error: { code: 'UNAUTHORIZED', message: 'Invalid API key' } },
      { status: 401 }
    )
  })
]
