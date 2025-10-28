import { test, expect } from '@playwright/test';

const telegramStub = `window.Telegram={WebApp:{initData:'',platform:'web',version:'7.0',colorScheme:'dark',ready:()=>{},expand:()=>{},onEvent:()=>{},offEvent:()=>{},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}},openInvoice:()=>{},showScanQrPopup:()=>{},closeScanQrPopup:()=>{},requestWriteAccess:()=>Promise.resolve(true),requestContact:()=>Promise.resolve(true)}}`;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(telegramStub);
  await page.route('**/*', (route) => route.fulfill({ status: 200, body: '[]', contentType: 'application/json' }));
});

test('applies dark theme', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('[data-theme="dark"]').first()).toBeVisible();
});
