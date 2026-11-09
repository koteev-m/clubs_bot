import { expect, test } from '@playwright/test';
import { telegramTheme } from './telegram-theme';
import { clubToday, type CalendarView } from '../src/modules/client/calendar';

const club = '9007199254740999';
const synthetic = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const entry =
  '/app?mode=client&screen=calendar&resource=' +
  club +
  '#tgWebAppData=' +
  encodeURIComponent(synthetic) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';

test('private calendar configures nights on mobile and resolves a lost response without another mutation', async ({
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
    reconciliation: {
      businessDate: clubToday(profile.timezone),
      storedDates: [],
    },
  };
  const mutations: { key: string | undefined; body: object }[] = [];
  const resolutions: { key: string | undefined; body: object }[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/profile')) return route.fulfill({ json: profile });
    if (path.endsWith('/calendar/commands/resolve')) {
      resolutions.push({
        key: request.headers()['idempotency-key'],
        body: request.postDataJSON(),
      });
      return route.fulfill({ json: { status: 'completed', calendar: view } });
    }
    if (path.endsWith('/calendar')) {
      if (request.method() === 'GET') return route.fulfill({ json: view });
      const body = request.postDataJSON();
      mutations.push({ key: request.headers()['idempotency-key'], body });
      if (mutations.length === 1) {
        view = {
          ...view,
          version: '2',
          weekly: body.weekly,
          exceptions: body.exceptions.map((row: object) => ({
            open: null,
            close: null,
            nextDay: false,
            ...row,
          })),
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
  await expect(
    page.getByRole('heading', { name: 'Календарь клуба' }),
  ).toBeVisible();
  await expect(
    page.getByRole('tab', { name: 'Предстоящие ночи' }),
  ).toHaveAttribute('aria-selected', 'true');
  await telegramTheme(page, 'light');
  await page.getByRole('tab', { name: 'Постоянное расписание' }).click();
  const friday = page.getByRole('group', { name: 'Пятница' });
  await friday.getByLabel('Проводим вечеринку').check();
  await friday.getByLabel('Время открытия').fill('00:00');
  await friday.getByLabel('Время закрытия').fill('12:00');
  await friday.getByLabel('День открытия').selectOption('next');
  await friday.getByLabel('День закрытия').selectOption('same');
  await expect(
    friday.getByText(
      'Вечеринка пятницы. Открытие: суббота, 00:00. Закрытие: суббота, 12:00.',
    ),
  ).toBeVisible();
  expect(mutations).toHaveLength(0);
  await friday.evaluate((node) => {
    const main = node.closest('main')!;
    main.scrollTop +=
      node.getBoundingClientRect().top - main.getBoundingClientRect().top;
  });
  await page.screenshot({
    path: test.info().outputPath('calendar-weekly-light-320.png'),
    fullPage: true,
  });
  await friday.locator('[aria-live]').scrollIntoViewIfNeeded();
  await page.screenshot({
    path: test.info().outputPath('calendar-midnight-interval-light-320.png'),
    fullPage: true,
  });
  await page.getByRole('tab', { name: 'Особые даты' }).click();
  await page.getByLabel('Дата вечеринки').fill('2026-11-06');
  await page
    .getByRole('button', { name: 'Отменить ночь', exact: true })
    .click();
  await page.getByLabel('Дата праздника').fill('2026-11-04');
  await page.getByRole('button', { name: 'Добавить праздничную ночь' }).click();
  expect(
    await page.locator('.po-main').evaluate((node) => node.scrollWidth),
  ).toBeLessThanOrEqual(320);
  await page.locator('.po-main').evaluate((node) => {
    node.scrollTop = 0;
  });
  await page.screenshot({
    path: test.info().outputPath('calendar-special-light-320.png'),
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Сохранить расписание' }).click();
  await expect(
    page.getByRole('heading', { name: 'Проверьте новое расписание' }),
  ).toBeVisible();
  expect(mutations).toHaveLength(0);
  await page.screenshot({
    path: test.info().outputPath('calendar-confirmation-light-320.png'),
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Подтвердить расписание' }).click();
  await expect(
    page.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.getByRole('tab', { name: 'Предстоящие ночи' }).click();
  await expect(
    page.locator('.cc-preview').getByText('Ночь пятницы'),
  ).toBeVisible();
  await expect(
    page.getByRole('button', {
      name: 'Перенести мои правки на актуальную версию',
    }),
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Назад' }).click();
  await expect(
    page.getByRole('heading', { name: 'Календарь клуба' }),
  ).toBeVisible();
  await page.getByRole('tab', { name: 'Постоянное расписание' }).click();
  await expect(friday.getByLabel('Время открытия')).toHaveValue(/00:00/);
  await page
    .getByRole('button', { name: 'Проверить результат операции' })
    .click();
  await expect(
    page.locator('.cc-preview').getByText('Ночь пятницы'),
  ).toBeVisible();
  await expect(
    page
      .locator('.cc-preview')
      .getByText('суббота, 14.11.2026 00:00 UTC+00:00'),
  ).toBeVisible();
  await expect(
    page
      .locator('.cc-preview')
      .getByText('суббота, 14.11.2026 12:00 UTC+00:00'),
  ).toBeVisible();
  await expect(
    page.getByText('Операция выполнена. Показано актуальное расписание.', {
      exact: true,
    }),
  ).toBeVisible();
  expect(mutations).toHaveLength(1);
  expect(resolutions).toHaveLength(1);
  expect(mutations[0].key).toBeTruthy();
  expect(mutations[0]).toEqual(resolutions[0]);
  expect(mutations[0].body).toMatchObject({
    expectedVersion: '1',
    timezone: 'Europe/London',
    exceptions: [{ date: '2026-11-06', mode: 'closed' }],
    holidays: [{ date: '2026-11-04', mode: 'open' }],
    weekly: [{ day: 5, startNextDay: true, nextDay: false }],
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await telegramTheme(page, 'dark');
  await page.locator('.po-main').evaluate((node) => {
    node.scrollTop = 0;
  });
  await page.screenshot({
    path: test.info().outputPath('calendar-upcoming-dark-390.png'),
    fullPage: true,
  });
  // Telegram launch data is memory-only: simulate reopening from Telegram.
  await page.goto('about:blank');
  await page.goto(entry);
  await expect(
    page.locator('.cc-preview').getByText('Ночь пятницы'),
  ).toBeVisible();
  await page.getByRole('tab', { name: 'Постоянное расписание' }).click();
  await expect(friday.getByLabel('Время закрытия')).toHaveValue(/12:00/);
  await expect(friday.getByLabel('День открытия')).toHaveValue('next');
  await page.getByRole('tab', { name: 'Особые даты' }).click();
  await expect(
    page.getByRole('group', { name: '06.11.2026' }).getByLabel('Режим'),
  ).toHaveValue('closed');
  await page.getByRole('tab', { name: 'Предстоящие ночи' }).click();
  await page
    .getByRole('button', { name: 'Сформировать ближайшие ночи' })
    .click();
  expect(mutations).toHaveLength(1);
  await page.getByRole('button', { name: 'Подтвердить формирование' }).click();
  await expect.poll(() => mutations.length).toBe(2);
  expect(mutations[1].body).toMatchObject({
    reconcile: true,
    expectedVersion: '2',
    weekly: [],
    exceptions: [],
    holidays: [],
  });
});
