import { test, expect } from '@playwright/test';

// stub Telegram SDK
const telegramStub = `window.Telegram={WebApp:{initData:'',platform:'web',version:'7.0',ready:()=>{},expand:()=>{},colorScheme:'light',onEvent:()=>{},offEvent:()=>{},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}},openInvoice:()=>{},showScanQrPopup:()=>{},closeScanQrPopup:()=>{},requestWriteAccess:()=>Promise.resolve(true),requestContact:()=>Promise.resolve(true)}}`;

test.beforeEach(async ({ page }) => {
  await page.addInitScript(telegramStub);
  await page.route('**/api/clubs', (route) =>
    route.fulfill({ json: [{ id: 1, name: 'Club' }] }),
  );
  await page.route('**/api/clubs/1/nights?limit=8', (route) =>
    route.fulfill({ json: [{ startUtc: '2024', name: 'Night' }] }),
  );
  await page.route('**/api/clubs/1/nights/2024/tables/free', (route) =>
    route.fulfill({
      json: [{ id: 1, number: 'A1', capacity: 4, status: 'FREE' }],
    }),
  );
  await page.route('**/api/support/tickets/my', (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route('**/assets/halls/club_1.geojson', (route) =>
    route.fulfill({
      json: {
        type: 'FeatureCollection',
        features: [
          {
            id: 1,
            geometry: {
              type: 'Polygon',
              coordinates: [
                [
                  [10, 10],
                  [100, 10],
                  [100, 100],
                  [10, 100],
                  [10, 10],
                ],
              ],
            },
          },
        ],
      },
    }),
  );
  await page.route('**/assets/halls/club_1.png', (route) =>
    route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200"></svg>',
    }),
  );
});

test('guest flow selects table', async ({ page }) => {
  await page.goto('/?mode=guest');
  await page.getByRole('combobox').nth(0).selectOption('1');
  await page.getByRole('combobox').nth(1).selectOption('2024');
  await page.click('polygon');
  await expect(page.locator('input[type="number"]')).toBeVisible();
});
