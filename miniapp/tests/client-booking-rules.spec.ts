import { expect, test } from '@playwright/test';
import type { BookingRulesView } from '../src/modules/client/ClubBookingRules';

const club = '9007199254740999';
const launch = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const entry =
  '/app?mode=client&screen=settings&resource=' +
  club +
  '#tgWebAppData=' +
  encodeURIComponent(launch) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';

test('configures and reopens saved rules on mobile after retrying the exact command', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 620 });
  const profile = {
    id: club,
    clientId: '21',
    clientName: 'Организация',
    name: 'Клуб',
    description: '',
    timezone: 'Europe/Berlin',
    countryCode: 'DE',
    city: 'Берлин',
    address: 'Test address',
    unit: '',
    version: '1',
    savedAt: '2026-11-03T12:00:00Z',
    permissions: ['client.club.read'],
    availableActions: [],
  };
  let view: BookingRulesView = {
    clubId: club,
    name: profile.name,
    timezone: profile.timezone,
    version: '1',
    canWrite: true,
    rules: {
      cutoffMinutes: 120,
      holdMinutes: null,
      retentionMinutes: 30,
    },
    nights: [],
    invalidDates: [],
  };
  const patches: { key?: string; body: object }[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/profile')) return route.fulfill({ json: profile });
    if (!path.endsWith('/booking-rules')) return route.fulfill({ status: 404 });
    if (request.method() === 'GET') return route.fulfill({ json: view });
    const { expectedVersion, ...rules } = request.postDataJSON();
    patches.push({
      key: request.headers()['idempotency-key'],
      body: { ...rules, expectedVersion },
    });
    if (patches.length === 1) {
      view = {
        ...view,
        version: '2',
        rules,
        nights: [
          {
            businessDate: '2026-10-24',
            opensAt: '2026-10-24T22:00+02:00',
            closesAt: '2026-10-25T04:00+01:00',
            cutoffAt: '2026-10-25T02:30+01:00',
            cutoffAtOrBeforeOpen: false,
          },
        ],
      };
      return route.abort('connectionreset');
    }
    return route.fulfill({ json: view });
  });
  await page.goto(entry);
  const open = () =>
    page.getByRole('button', { name: /Правила бронирования/ }).click();
  await open();
  const cutoff = page.getByRole('textbox', {
    name: /Приём онлайн-броней/,
  });
  const arrival = page.getByRole('textbox', {
    name: /Самый поздний приезд/,
  });
  const hold = page.getByRole('textbox', {
    name: /Время на оформление/,
  });
  const retention = page.getByRole('textbox', {
    name: /Ожидание при опоздании/,
  });
  await expect(cutoff).toHaveValue('120');
  await expect(arrival).toHaveCount(0);
  await expect(hold).toHaveValue('');
  await expect(retention).toHaveValue('30');
  await cutoff.fill('90');
  await hold.fill('12');
  await retention.fill('45');
  expect(
    await page.locator('.po-main').evaluate((node) => node.scrollWidth),
  ).toBeLessThanOrEqual(320);
  await page.getByRole('button', { name: 'Сохранить правила' }).click();
  await expect(
    page.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await expect(page.getByText('Сохранено', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(hold).toHaveValue('12');
  await page.getByRole('button', { name: 'Повторить ту же операцию' }).click();
  await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
  expect(patches).toHaveLength(2);
  expect(patches[0].key).toBeTruthy();
  expect(patches[1]).toEqual(patches[0]);
  expect(patches[0].body).toEqual({
    expectedVersion: '1',
    cutoffMinutes: 90,
    holdMinutes: 12,
    retentionMinutes: 45,
  });
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await open();
  await expect(hold).toHaveValue('12');
  await expect(retention).toHaveValue('45');
  await expect(
    page.getByText(/Примеры для вечеринки 24.10.2026/),
  ).toBeVisible();
  await expect(page.getByText(/Если выбрать стол/)).toContainText('21:12');
  await expect(
    page.getByText(/Если гость указал приезд к открытию/),
  ).toContainText('22:45');
  await page.getByText('Вечеринка 24.10.2026').click();
  await expect(
    page.locator('time[datetime="2026-10-25T02:30+01:00"]'),
  ).toBeVisible();
  await expect(page.getByText(/Гость указывает ориентировочное время/)).toBeVisible();
});

test('clears saved booking rules and edits through a denied read and a failed reload on mobile', async ({page}) => {
  let status = 200;
  let hold = 17;
  await page.route('**/api/client/clubs/*/booking-rules', (route) =>
    route.fulfill({status, json: status === 200 ? {
      clubId: club, name:'Клуб', timezone:'UTC', canWrite:true, version:'2',
      rules:{cutoffMinutes:60, holdMinutes:hold, retentionMinutes:30}, nights:[], invalidDates:[],
    } : {code:status === 404 ? 'not_found' : 'internal_error'}}),
  );
  await page.goto(entry.replace('screen=settings', 'screen=booking-rules'));
  const input = page.getByRole('textbox', {name:/Время на оформление/});
  await expect(input).toHaveValue('17');
  await input.fill('77');
  status = 404;
  await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await expect(page.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(input).toHaveCount(0);
  status = 503;
  await page.getByRole('button', {name:'Повторить загрузку'}).click();
  await expect(page.getByText(/Не удалось загрузить данные/)).toBeVisible();
  await expect(input).toHaveCount(0);
  hold = 29;
  status = 200;
  await page.getByRole('button', {name:'Повторить загрузку'}).click();
  await expect(input).toHaveValue('29');
});
