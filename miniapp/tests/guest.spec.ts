import { test, expect } from '@playwright/test';

// stub Telegram SDK
const telegramStub = `window.Telegram={WebApp:{initData:'',platform:'web',version:'7.0',ready:()=>{},expand:()=>{},colorScheme:'light',onEvent:()=>{},offEvent:()=>{},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}},openInvoice:()=>{},showScanQrPopup:()=>{},closeScanQrPopup:()=>{},requestWriteAccess:()=>Promise.resolve(true),requestContact:()=>Promise.resolve(true)}}`;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(telegramStub);
  await page.route('**/api/clubs', (route) => route.fulfill({ json: [{ id: 1, name: 'Club' }] }));
  await page.route('**/api/clubs/1/nights?limit=8', (route) => route.fulfill({ json: [{ startUtc: '2024', name: 'Night' }] }));
  await page.route('**/api/clubs/1/nights/2024/tables/free', (route) => route.fulfill({ json: [{ id: 1, number: 'A1', capacity: 4, status: 'FREE' }] }));
});

test('guest flow selects table', async ({ page }) => {
  await page.goto('/?mode=guest');
  await page.selectOption('select', { value: '1' });
  await page.selectOption('select:nth-of-type(2)', { value: '2024' });
  await page.click('polygon');
  await expect(page.locator('input[type="number"]')).toBeVisible();
});
