import { expect, test } from '@playwright/test';

const telegramStub = `window.Telegram={WebApp:{initData:'signed-mobile-init-data',platform:'ios',version:'7.0',ready:()=>{},expand:()=>{},colorScheme:'light',onEvent:()=>{},offEvent:()=>{},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}},openInvoice:()=>{},showScanQrPopup:()=>{},closeScanQrPopup:()=>{},requestWriteAccess:()=>Promise.resolve(true),requestContact:()=>Promise.resolve(true)}}`;

test.use({
  viewport: { width: 390, height: 844 },
  hasTouch: true,
  isMobile: true,
});

test('guest support stays bounded and usable at a mobile viewport', async ({ page }) => {
  let continued = false;
  const authenticatedRequests: string[] = [];

  await page.addInitScript(telegramStub);
  await page.route('**/api/support/tickets/my', async (route) => {
    authenticatedRequests.push(route.request().headers()['x-telegram-init-data'] ?? '');
    await route.fulfill({
      json: [
        {
          id: 41,
          clubId: 7,
          topic: 'booking',
          status: continued ? 'in_progress' : 'resolved',
          updatedAt: '2026-08-21T10:00:00Z',
          lastMessagePreview: continued ? 'Продолжение' : 'Ответ поддержки',
          lastSenderType: continued ? 'guest' : 'agent',
        },
      ],
    });
  });
  await page.route(/\/api\/support\/tickets\/my\/41$/, async (route) => {
    authenticatedRequests.push(route.request().headers()['x-telegram-init-data'] ?? '');
    await route.fulfill({
      json: {
        ticket: {
          id: 41,
          clubId: 7,
          topic: 'booking',
          status: continued ? 'in_progress' : 'resolved',
          createdAt: '2026-08-21T09:00:00Z',
          updatedAt: continued ? '2026-08-21T10:02:00Z' : '2026-08-21T10:00:00Z',
        },
        messages: [
          {
            id: 101,
            senderType: 'guest',
            text: 'Первое сообщение',
            attachments: null,
            createdAt: '2026-08-21T09:00:00Z',
          },
          {
            id: 102,
            senderType: 'agent',
            text: 'Ответ поддержки',
            attachments: null,
            createdAt: '2026-08-21T10:00:00Z',
          },
          ...(continued
            ? [
                {
                  id: 103,
                  senderType: 'guest',
                  text: 'Продолжение',
                  attachments: null,
                  createdAt: '2026-08-21T10:02:00Z',
                },
              ]
            : []),
        ],
      },
    });
  });
  await page.route(/\/api\/support\/tickets\/41\/messages$/, async (route) => {
    authenticatedRequests.push(route.request().headers()['x-telegram-init-data'] ?? '');
    expect(route.request().postDataJSON()).toEqual({ text: 'Продолжение' });
    continued = true;
    await route.fulfill({
      json: {
        messageId: 103,
        ticketId: 41,
        senderType: 'guest',
        createdAt: '2026-08-21T10:02:00Z',
      },
    });
  });

  await page.goto(
    '/?mode=guest-support#tgWebAppData=signed-mobile-init-data&tgWebAppVersion=7.0&tgWebAppPlatform=ios',
  );
  await expect(page.getByRole('heading', { name: 'Мои обращения' })).toBeVisible();
  await expect(page.getByText('Ответ поддержки')).toBeVisible();
  await page.getByRole('button', { name: 'Открыть обращение 41' }).click();
  await expect(page.getByLabel('История обращения')).toBeVisible();
  await page.getByLabel('Новое сообщение').fill('  Продолжение  ');
  await page.getByRole('button', { name: 'Отправить' }).click();

  await expect(page.getByText('Сообщение отправлено')).toBeVisible();
  await expect(page.getByTestId('guest-support-ticket-status')).toHaveText('В работе');
  await expect(page.getByText('Продолжение')).toBeVisible();
  await expect(page.getByText(/забронировать|оплатить|лояльность|музыка|iBota/i)).toHaveCount(0);
  await expect(page.getByLabel(/клуб/i)).toHaveCount(0);

  const safeAreaStyles = await page.evaluate(() => ({
    header: document.querySelector('[data-testid="guest-support-header"]')?.getAttribute('style'),
    content: document.querySelector('[data-testid="guest-support-content"]')?.getAttribute('style'),
    fitsViewport: document.documentElement.scrollWidth <= window.innerWidth,
  }));
  expect(safeAreaStyles.header).toContain('safe-area-inset-top');
  expect(safeAreaStyles.content).toContain('safe-area-inset-bottom');
  expect(safeAreaStyles.fitsViewport).toBe(true);
  expect(authenticatedRequests.length).toBeGreaterThanOrEqual(4);
  expect(authenticatedRequests).toEqual(
    Array.from({ length: authenticatedRequests.length }, () => 'signed-mobile-init-data'),
  );
});
