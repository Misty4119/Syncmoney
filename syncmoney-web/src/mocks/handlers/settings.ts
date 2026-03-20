import { http, HttpResponse } from 'msw'

export const settingsHandlers = [

  http.get('/api/settings', () => {
    return HttpResponse.json({
      success: true,
      data: {
        theme: 'dark',
        language: 'en-US',
        serverName: 'Minecraft Server',
        currencyName: 'Coins'
      }
    })
  }),


  http.post('/api/settings/theme', async ({ request }) => {
    const body = await request.json() as { theme: string }
    return HttpResponse.json({
      success: true,
      data: { theme: body.theme }
    })
  }),


  http.post('/api/settings/language', async ({ request }) => {
    const body = await request.json() as { language: string }
    return HttpResponse.json({
      success: true,
      data: { language: body.language }
    })
  })
]
