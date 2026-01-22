import { test, expect } from '@playwright/test';

const telegramStub = `window.Telegram={WebApp:{initData:'',platform:'web',version:'7.0',ready:()=>{},expand:()=>{},colorScheme:'light',events:{},onEvent:function(n,cb){this.events[n]=cb;},offEvent:function(n){delete this.events[n];},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}},openInvoice:()=>{},showScanQrPopup:function(){const cb=this.events['qrTextReceived'];cb&&cb({data:'code'});},closeScanQrPopup:()=>{}}}`;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(telegramStub);
});

test('entry QR flow keeps scanner open on denied outcome', async ({ page }) => {
  await page.route('**/api/host/checkin/scan', (route) =>
    route.fulfill({ json: { outcomeStatus: 'DENIED', denyReason: 'ALREADY_USED', subject: { kind: 'INVITATION' } } }),
  );
  await page.goto('/?mode=entry');
  await page.fill('input[placeholder="Club ID"]', '1');
  await page.fill('input[placeholder="Event ID"]', '2');
  await page.click('text=Сканер');
  await page.click('text=Сканировать QR');
  await expect(page.locator('text=QR уже использован')).toBeVisible();
  await expect(page.locator('text=ARRIVED')).toHaveCount(0);
});
