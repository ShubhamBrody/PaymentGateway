import { expect, test, type Page } from '@playwright/test'

const PASSWORD = 'correct-horse-battery-staple'

async function signUp(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Business name').fill('Playwright Store')
  await page.getByLabel('Email').fill(`merchant-${crypto.randomUUID()}@example.com`)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible()
}

async function createPayment(page: Page, amount: string, description: string) {
  await page.getByLabel('Amount (INR)').fill(amount)
  await page.getByLabel('Description').fill(description)
  await page.getByRole('button', { name: 'Create payment' }).click()
  await expect(page.getByRole('heading', { level: 2, name: /249\.99|1,500\.00/ })).toBeVisible()
}

test('a merchant pays through the queue, retries with the same key, and sees one receipt', async ({ page }) => {
  await signUp(page)
  await createPayment(page, '249.99', 'Headphones')

  await page.getByLabel('Bank behaviour').selectOption('APPROVE')
  await page.getByRole('button', { name: 'Pay', exact: true }).click()

  await expect(page.getByTestId('request-status')).toHaveText('SUCCEEDED')
  await expect(page.getByTestId('receipt-number')).toHaveText(/^RCPT-[0-9A-F]{12}$/)
  await expect(page.getByTestId('replayed')).toHaveText('No')
  const key = await page.getByTestId('request-key').textContent()
  const receipt = await page.getByTestId('receipt-number').textContent()

  await page.getByRole('button', { name: 'Retry same request' }).click()

  await expect(page.getByTestId('replayed')).toHaveText('Yes')
  await expect(page.getByTestId('request-key')).toHaveText(key!)
  await expect(page.getByTestId('receipt-number')).toHaveText(receipt!)
  await expect(page.locator('.detail tbody tr')).toHaveCount(1)
  await expect(page.getByRole('button', { name: 'Pay', exact: true })).toBeDisabled()

  await page.getByRole('button', { name: 'System' }).click()
  await expect(page.getByText('payments:requests')).toBeVisible()
  await expect(page.getByTestId('receipts-count')).not.toHaveText('0')
})

test('a declined payment and a lost bank request recover, ending with exactly one receipt', async ({ page }) => {
  await signUp(page)
  await createPayment(page, '1500', 'Monthly plan')

  await page.getByLabel('Bank behaviour').selectOption('DECLINE')
  await page.getByRole('button', { name: 'Pay', exact: true }).click()
  await expect(page.getByTestId('request-status')).toHaveText('DECLINED')
  await expect(page.getByTestId('receipt-number')).toHaveCount(0)

  await page.getByLabel('Bank behaviour').selectOption('TIMEOUT_THEN_APPROVE')
  await page.getByRole('button', { name: 'Pay', exact: true }).click()

  await expect(page.getByTestId('request-status')).toHaveText('SUCCEEDED')
  await expect(page.getByTestId('receipt-number')).toHaveText(/^RCPT-/)
  const rows = page.locator('.detail tbody tr')
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0)).toContainText('DECLINED')
  await expect(rows.nth(1)).toContainText('SUCCEEDED')
  await expect(rows.nth(1).locator('td').nth(3)).toHaveText('2')
})
