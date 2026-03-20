import { test, expect } from '@playwright/test'

test.describe('Dashboard', () => {
  test.beforeEach(async ({ page }) => {
    // Login first
    await page.route('/api/system/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: {
            plugin: { name: 'Syncmoney', version: '1.0.0', onlinePlayers: 5, maxPlayers: 100 },
            circuitBreaker: { state: 'NORMAL' }
          }
        })
      })
    })
    
    await page.route('/api/system/metrics', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: { economy: { transactionsToday: 156 } }
        })
      })
    })
    
    await page.goto('/login')
    await page.fill('#api-key', 'test-api-key')
    await page.click('button[type="submit"]')
    await page.waitForURL('/dashboard')
  })

  test('should display dashboard stats', async ({ page }) => {
    // Check stats cards are visible
    await expect(page.locator('.stat-grid, .stats-grid').first()).toBeVisible()
    
    // Check circuit breaker status
    await expect(page.locator('text=保險絲狀態')).toBeVisible()
  })

  test('should display page title', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('儀表板')
  })
})
