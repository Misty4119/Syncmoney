import { http, HttpResponse } from 'msw'
import { auditFixtures } from '../fixtures/audit'

export const auditHandlers = [

  http.get('/api/audit/search', ({ request }) => {
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') || '1')
    const pageSize = parseInt(url.searchParams.get('pageSize') || '20')
    const player = url.searchParams.get('player')
    const type = url.searchParams.get('type')
    const startTime = url.searchParams.get('startTime')

    let filteredData = [...auditFixtures]


    if (player) {
      filteredData = filteredData.filter(r =>
        r.playerName.toLowerCase().includes(player.toLowerCase())
      )
    }

    if (type) {
      filteredData = filteredData.filter(r => r.type === type)
    }

    if (startTime) {
      const start = parseInt(startTime)
      filteredData = filteredData.filter(r => r.timestamp >= start)
    }

    const totalItems = filteredData.length
    const totalPages = Math.ceil(totalItems / pageSize)
    const startIndex = (page - 1) * pageSize
    const paginatedData = filteredData.slice(startIndex, startIndex + pageSize)

    return HttpResponse.json({
      success: true,
      data: paginatedData,
      pagination: {
        page,
        pageSize,
        totalItems,
        totalPages
      }
    })
  })
]
