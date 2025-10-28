import { test, expect } from '@playwright/test';

const telegramStub = `window.Telegram={WebApp:{initData:'',platform:'web',version:'7.0',ready:()=>{},expand:()=>{},colorScheme:'light',events:{},onEvent:function(n,cb){this.events[n]=cb;},offEvent:function(n){delete this.events[n];},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}},openInvoice:()=>{},showScanQrPopup:function(){const cb=this.events['qrTextReceived'];cb&&cb('code');},closeScanQrPopup:()=>{}}}`;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(telegramStub);
  await page.route('**/api/checkin/qr', (route) => route.fulfill({ json: { ok: true } }));
});

test('entry QR flow', async ({ page }) => {
  await page.goto('/?mode=entry');
  await page.click('text=Сканировать QR');
  await expect(page.locator('text=ARRIVED')).toBeVisible();
});
