import { expect, test } from '@playwright/test';
import type { CalendarView } from '../src/modules/client/calendar';

const club = '9007199254740999';
const synthetic = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const entry =
  '/app?mode=client&screen=club&resource=' +
  club +
  '#tgWebAppData=' +
  encodeURIComponent(synthetic) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';

test('private calendar configures nights on mobile and safely retries a lost response', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 620 });
  const profile = {
    id: club,
    clientId: '21',
    clientName: 'Организация',
    name: 'Клуб',
    description: '',
    timezone: 'Europe/London',
    countryCode: 'GB',
    city: 'Лондон',
    address: 'Test address',
    unit: '',
    version: '1',
    savedAt: '2026-11-03T12:00:00Z',
    permissions: ['client.club.read', 'client.club.calendar.write'],
    availableActions: [],
  };
  let view: CalendarView = {
    clubId: club,
    name: profile.name,
    timezone: profile.timezone,
    version: '1',
    canWrite: true,
    weekly: [],
    holidays: [],
    exceptions: [],
    nights: [],
    invalidDates: [],
  };
  const mutations: { key: string | undefined; body: object }[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/profile')) return route.fulfill({ json: profile });
    if (path.endsWith('/calendar')) {
      if (request.method() === 'GET') return route.fulfill({ json: view });
      const body = request.postDataJSON();
      mutations.push({ key: request.headers()['idempotency-key'], body });
      if (mutations.length === 1) {
        view = {
          ...view,
          version: '2',
          weekly: body.weekly,
          exceptions: body.exceptions,
          holidays: body.holidays,
          nights: [
            {
              businessDate: '2026-11-13',
              opensAt: '2026-11-14T00:00Z',
              closesAt: '2026-11-14T12:00Z',
              source: 'WEEKEND_RULE',
            },
          ],
        };
        return route.abort('connectionreset');
      }
      return route.fulfill({ json: view });
    }
    return route.fulfill({ status: 404, json: { code: 'not_found' } });
  });
  await page.goto(entry);
  await page.getByRole('button', { name: /Календарь клуба/ }).click();
  await expect(
    page.getByRole('heading', { name: 'Календарь клуба' }),
  ).toBeVisible();
  const friday = page.getByRole('group', { name: 'Пятница' });
  await friday.getByLabel('Проводим вечеринку').check();
  await friday.getByLabel('Время открытия').fill('00:00');
  await friday.getByLabel('Время закрытия').fill('12:00');
  await friday
    .getByLabel('Когда открываемся относительно дня вечеринки')
    .selectOption('next');
  await friday
    .getByLabel('Закрытие на следующий день после открытия', { exact: true })
    .uncheck();
  await expect(
    friday.getByText(
      'Вечеринка пятницы. Открытие: суббота, 00:00. Закрытие: суббота, 12:00.',
    ),
  ).toBeVisible();
  expect(mutations).toHaveLength(0);
  await page.getByLabel('Дата вечеринки').fill('2026-11-06');
  await page
    .getByRole('button', { name: 'Отменить ночь', exact: true })
    .click();
  await page.getByLabel('Дата праздника').fill('2026-11-04');
  await page.getByRole('button', { name: 'Добавить праздничную ночь' }).click();
  expect(
    await page.locator('.po-main').evaluate((node) => node.scrollWidth),
  ).toBeLessThanOrEqual(320);
  await page.getByRole('button', { name: 'Сохранить расписание' }).click();
  await expect(
    page.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByText('Ночь 13.11.2026')).toBeVisible();
  await expect(
    page.getByRole('button', {
      name: 'Перенести мои правки на актуальную версию',
    }),
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Профиль клуба' }).click();
  await expect(
    page.getByRole('heading', { name: 'Календарь клуба' }),
  ).toBeVisible();
  await expect(friday.getByLabel('Время открытия')).toHaveValue(/00:00/);
  await page.getByRole('button', { name: 'Повторить ту же операцию' }).click();
  await expect(page.getByText('Ночь 13.11.2026')).toBeVisible();
  await expect(
    page.getByText('суббота, 14.11.2026 00:00 UTC+00:00'),
  ).toBeVisible();
  await expect(
    page.getByText('суббота, 14.11.2026 12:00 UTC+00:00'),
  ).toBeVisible();
  expect(mutations).toHaveLength(2);
  expect(mutations[0].key).toBeTruthy();
  expect(mutations[0]).toEqual(mutations[1]);
  expect(mutations[0].body).toMatchObject({
    expectedVersion: '1',
    timezone: 'Europe/London',
    exceptions: [{ date: '2026-11-06', mode: 'closed' }],
    holidays: [{ date: '2026-11-04', mode: 'open' }],
    weekly: [{ day: 5, startNextDay: true, nextDay: false }],
  });
  await page.getByRole('button', { name: 'Профиль клуба' }).click();
  await page.getByRole('button', { name: /Календарь клуба/ }).click();
  await expect(page.getByText('Ночь 13.11.2026')).toBeVisible();
  await expect(friday.getByLabel('Время закрытия')).toHaveValue(/12:00/);
  await expect(
    friday.getByLabel('Когда открываемся относительно дня вечеринки'),
  ).toHaveValue('next');
  await expect(
    page.getByRole('group', { name: '06.11.2026' }).getByLabel('Режим'),
  ).toHaveValue('closed');
  await page
    .getByRole('button', { name: 'Сформировать ближайшие ночи' })
    .click();
  await expect.poll(() => mutations.length).toBe(3);
  expect(mutations[2].body).toMatchObject({
    reconcile: true,
    expectedVersion: '2',
    weekly: [],
    exceptions: [],
    holidays: [],
  });
});
