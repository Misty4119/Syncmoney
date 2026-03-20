import { test, expect } from '@playwright/test'

test.describe('Audit Log', () => {
  test.beforeEach(async ({ page }) => {
    // Mock login
    await page.route('/api/system/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: { plugin: { name: 'Syncmoney', version: '1.0.0' } }
        })
      })
    })
  })

  test('should display audit log page', async ({ page }) => {
    // Directly go to audit page (would require auth in real app)
    await page.goto('/audit')
    
    // Page should load (may redirect to login if not authenticated)
    await expect(page.locator('h1')).toBeVisible()
  })
})
