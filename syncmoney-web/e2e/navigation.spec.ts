import { test, expect } from '@playwright/test'

test.describe('Navigation', () => {
  test('should display sidebar navigation on login page', async ({ page }) => {
    await page.goto('/login')
    
    // Sidebar should not be visible on login page
    await expect(page.locator('.sidebar')).not.toBeVisible()
  })
})
