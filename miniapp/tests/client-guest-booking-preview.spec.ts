import { createHash } from 'node:crypto';
import { expect, test } from '@playwright/test';

const clubId = '9007199254740999';
const hallId = '9007199254741000';
const tableId = '9007199254741001';
const launch = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const entry =
  '/app?mode=client&screen=readiness&resource=' +
  clubId +
  '#tgWebAppData=' +
  encodeURIComponent(launch) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';
const plan = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+nmWQAAAAASUVORK5CYII=',
  'base64',
);

test('opens the owner preview with one read-only snapshot on map, list and card', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let revoked = false;
  let denyPlan = false;
  let snapshotError = false;
  let firstNightStatus = 'FREE';
  const requests: string[] = [];
  const profile = {
    id: clubId,
    clientId: '21',
    clientName: 'Организация',
    name: 'Ночной клуб',
    description: '',
    timezone: 'Europe/Berlin',
    countryCode: 'DE',
    city: 'Берлин',
    address: 'Test address',
    unit: '',
    version: '1',
    savedAt: '2026-10-24T12:00:00Z',
    permissions: ['client.club.read'],
    availableActions: [],
    canPreviewBooking: true,
  };
  const layout = {
    clubId,
    permissions: ['client.club.read'],
    halls: [
      {
        id: hallId,
        name: 'Основной зал',
        layoutRevision: '1',
        plan: {
          contentType: 'image/png',
          sizeBytes: plan.length,
          sha256: createHash('sha256').update(plan).digest('hex'),
        },
        tables: [
          {
            id: tableId,
            hallId,
            tableNumber: 7,
            label: 'У окна',
            capacity: 6,
            bookingLinked: true,
            minDeposit: '5000',
            x: 0.55,
            y: 0.42,
          },
        ],
      },
    ],
  };
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    requests.push(request.method() + ' ' + path);
    if (revoked)
      return route.fulfill({ status: 404, json: { code: 'not_found' } });
    if (path.endsWith('/profile')) return route.fulfill({ json: profile });
    if (path.endsWith('/calendar'))
      return route.fulfill({ json: { clubId, name: profile.name, timezone: profile.timezone, version: '1', canWrite: false, weekly: [], holidays: [], exceptions: [], nights: [], invalidDates: [] } });
    if (path.endsWith('/booking-rules'))
      return route.fulfill({ json: { clubId, timezone: profile.timezone, version: '1', nights: [] } });
    if (path.endsWith('/layout'))
      return denyPlan
        ? route.fulfill({ status: 404, json: { code: 'not_found' } })
        : route.fulfill({ json: layout });
    if (path.endsWith('/plan'))
      return denyPlan
        ? route.fulfill({ status: 404, json: { code: 'not_found' } })
        : route.fulfill({ contentType: 'image/png', body: plan });
    if (path.endsWith('/availability')) {
      if (snapshotError)
        return route.fulfill({
          status: 500,
          json: { code: 'internal_error' },
        });
      const first = path.includes('/nights/21/');
      return route.fulfill({
        json: {
          clubId,
          hallId,
          nightId: first ? '21' : '22',
          layoutRevision: '1',
          businessDate: first ? '2026-10-24' : '2026-10-31',
          sampledAt: '2026-10-24T20:00:00Z',
          tables: [
            {
              id: tableId,
              status: first ? firstNightStatus : 'UNKNOWN',
              ...(first ? {} : { reason: 'EVENT_UNLINKED' }),
            },
          ],
        },
      });
    }
    if (path.endsWith('/booking-preview'))
      return route.fulfill({
        json: {
          ...layout,
          name: profile.name,
          timezone: profile.timezone,
          bookingBlocker: 'TABLE_MAPPING_REQUIRED',
          nights: [
            {
              nightId: '21',
              businessDate: '2026-10-24',
              opensAt: '2026-10-25T02:00+02:00',
              closesAt: '2026-10-25T08:00+01:00',
              cutoffAt: '2026-10-25T06:00+01:00',
              arrivalBy: '2026-10-25T02:30+01:00',
              eventStatus: 'VERIFIED',
            },
            {
              nightId: '22',
              businessDate: '2026-10-31',
              opensAt: '2026-11-01T00:00+01:00',
              closesAt: '2026-11-01T08:00+01:00',
              cutoffAt: '2026-11-01T06:00+01:00',
              arrivalBy: null,
              eventStatus: 'UNLINKED',
            },
          ],
        },
      });
    return route.fulfill({ status: 404, json: { code: 'not_found' } });
  });
  await page.goto(entry);
  const open = () =>
    page.getByRole('button', { name: /Гостевой предпросмотр/ }).click();
  await open();
  await expect(page.getByText(/25\.10\.2026 02:00 UTC\+02:00/)).toBeVisible();
  await expect(page.getByText(/25\.10\.2026 08:00 UTC\+01:00/)).toBeVisible();
  await expect(
    page.getByText('Событие связано, время проверено.'),
  ).toBeVisible();
  await page
    .getByRole('combobox', { name: 'Клубная ночь' })
    .selectOption('2026-10-31');
  await expect(page.getByText(/01\.11\.2026 00:00 UTC\+01:00/)).toBeVisible();
  await expect(page.getByText(/ещё не связано событие/)).toBeVisible();
  await page
    .getByRole('combobox', { name: 'Клубная ночь' })
    .selectOption('2026-10-24');
  const marker = page.getByTestId('table-marker-' + tableId);
  await expect(marker).toHaveAccessibleName(/Свободен/);
  const list = page.getByLabel('Столы на схеме');
  await expect(list.getByRole('button', { name: /Свободен/ })).toBeVisible();
  await expect(page.getByText(/Снимок на/)).toBeVisible();
  expect((await marker.boundingBox())?.width).toBe(44);
  await marker.tap();
  const guests = page.getByRole('spinbutton', { name: 'Количество гостей' });
  await guests.fill('4');
  await expect(guests).toHaveValue('4');
  await expect(page.getByText(/25\.10\.2026 02:30 UTC\+01:00/)).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Удержать стол' }),
  ).toBeDisabled();
  expect(
    await page.locator('.po-main').evaluate((el) => el.scrollWidth),
  ).toBeLessThanOrEqual(390);
  const card = page.getByRole('region', { name: 'Карточка стола' });
  await expect(card.getByText(/Доступность.*Свободен/)).toBeVisible();
  snapshotError = true;
  const refresh = page.getByRole('button', { name: 'Обновить занятость' });
  await refresh.click();
  await expect(
    page
      .getByRole('alert')
      .filter({ hasText: /Не удалось получить актуальный снимок/ }),
  ).toBeVisible();
  await expect(marker).toHaveAccessibleName(/Доступность не проверена/);
  await expect(
    card.getByText(/Доступность.*Доступность не проверена/),
  ).toBeVisible();
  snapshotError = false;
  firstNightStatus = 'OCCUPIED';
  await refresh.click();
  await expect(marker).toHaveAccessibleName(/Занят/);
  await expect(list.getByRole('button', { name: /Занят/ })).toBeVisible();
  await expect(card.getByText(/Доступность.*Занят/)).toBeVisible();
  await testInfo.attach('guest-table-card', {
    body: await card.screenshot(),
    contentType: 'image/png',
  });
  page.once('dialog', (dialog) => dialog.dismiss());
  await page.getByRole('button', { name: 'Назад' }).click();
  await expect(guests).toHaveValue('4');
  page.once('dialog', (dialog) => dialog.accept());
  // Readiness is still authorized; inject the plan denial only after its
  // metadata read finishes, so this tests the preview's lost-plan handling.
  const readyLayout = page.waitForResponse((response) => response.url().endsWith('/layout') && response.ok());
  await page.getByRole('button', { name: 'Назад' }).click();
  await readyLayout;
  denyPlan = true;
  await open();
  await expect(
    page.getByText('Доступ к сохранённой схеме утрачен.'),
  ).toBeVisible();
  await expect(marker).toHaveCount(0);
  denyPlan = false;
  await page.getByRole('button', { name: 'Повторить загрузку' }).click();
  await expect(marker).toBeVisible();
  revoked = true;
  await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await expect(
    page.getByText(/Доступ утрачен или запись недоступна/),
  ).toBeVisible();
  await expect(marker).toHaveCount(0);
  expect(
    requests.every((request) => request.startsWith('GET /api/client/')),
  ).toBe(true);
});
