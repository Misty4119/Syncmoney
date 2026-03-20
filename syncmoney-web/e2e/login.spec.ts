import { test, expect } from '@playwright/test'

test.describe('Login Flow', () => {
  test('should display login page correctly', async ({ page }) => {
    await page.goto('/login')
    
    // Check page title
    await expect(page.locator('h1')).toContainText('Syncmoney')
    await expect(page.locator('h2')).toContainText('登入')
    
    // Check input fields
    await expect(page.locator('#api-key')).toBeVisible()
    
    // Check login button
    await expect(page.locator('button[type="submit"]')).toContainText('登入')
  })
})
