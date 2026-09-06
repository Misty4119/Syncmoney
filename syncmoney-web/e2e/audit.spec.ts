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
    await page.addInitScript(() => localStorage.setItem('apiKey', 'test-api-key'))
    await page.route('**/api/nodes', route => route.fulfill({ json: { success: true, data: { nodes: [], centralMode: false } } }))
    await page.route('**/api/audit/**', route => route.fulfill({ json: { success: true, data: { records: [], hasMore: false } } }))
    await page.goto('/audit')
    await expect(page).toHaveURL(/\/audit$/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('審計日誌')
  })
})
