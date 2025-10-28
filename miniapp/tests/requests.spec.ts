import { test } from '@playwright/test';

const telegramStub = `window.Telegram={WebApp:{initData:'',platform:'web',version:'7.0',ready:()=>{},expand:()=>{},colorScheme:'light',onEvent:()=>{},offEvent:()=>{},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}},openInvoice:()=>{},showScanQrPopup:()=>{},closeScanQrPopup:()=>{},requestWriteAccess:()=>Promise.resolve(true),requestContact:()=>Promise.resolve(true)}}`;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(telegramStub);
  await page.route('**/*', (route) => route.fulfill({ status: 200, body: '[]', contentType: 'application/json' }));
});

test('request flows', async ({ page }) => {
  await page.goto('/?mode=guest');
  await page.evaluate(() => {
    // @ts-ignore
    window.Telegram.WebApp.requestWriteAccess();
    // @ts-ignore
    window.Telegram.WebApp.requestContact();
  });
});
