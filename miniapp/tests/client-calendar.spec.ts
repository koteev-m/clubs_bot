import { expect, test, type Locator } from '@playwright/test';
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

const emptyCalendar = (date: string): CalendarView => ({
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
    businessDate: date,
    storedDates: [],
  },
});

test('private calendar configures nights on mobile and resolves a lost response without another mutation', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 620 });
  let view = emptyCalendar(clubToday(profile.timezone));
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

async function expectReadableNightControls(group: Locator) {
  const controls = group.locator('select, input[type="time"]');
  await expect(controls).toHaveCount(4);
  const issues = await controls.evaluateAll((elements) => {
    const problems: string[] = [];
    const labels = elements.map((el) => el.closest('label')!);
    const container = labels[0].closest('.cc-rule')!.getBoundingClientRect();
    const rects = labels.map((label) => label.getBoundingClientRect());
    const canvas = document.createElement('canvas').getContext('2d')!;
    elements.forEach((el, index) => {
      const box = el.getBoundingClientRect();
      const label = labels[index];
      const title = label.querySelector('span')!.textContent!;
      const field = rects[index];
      if (
        box.left < container.left ||
        box.right > container.right ||
        box.left < 0 ||
        box.right > innerWidth ||
        box.height < 44
      )
        problems.push(title + ' выходит за границы поля');
      if (
        rects
          .slice(0, index)
          .some(
            (other) =>
              other.left < field.right &&
              field.left < other.right &&
              other.top < field.bottom &&
              field.top < other.bottom,
          )
      )
        problems.push(title + ' перекрывает другое поле');
      if (el instanceof HTMLSelectElement) {
        const style = getComputedStyle(el);
        canvas.font = style.fontSize + ' ' + style.fontFamily;
        const available =
          el.clientWidth -
          parseFloat(style.paddingLeft) -
          parseFloat(style.paddingRight);
        for (const option of el.options)
          if (canvas.measureText(option.text).width > available + 1)
            problems.push(title + ' обрезает «' + option.text + '»');
      }
    });
    return problems;
  });
  expect.soft(issues).toEqual([]);
}

test('shows the November 27–28 intersection and accepts both parties after midnight', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2026-11-25T12:00Z'));
  let view = emptyCalendar('2026-11-25');
  const writes: object[] = [];
  await page.route('**/api/**', (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/profile')) return route.fulfill({ json: profile });
    if (!path.endsWith('/calendar')) return route.fulfill({ status: 404 });
    if (request.method() === 'PATCH') {
      const body = request.postDataJSON();
      writes.push(body);
      view = { ...view, version: '2', weekly: body.weekly };
    }
    return route.fulfill({ json: view });
  });
  await page.goto(entry);
  await page.getByRole('tab', { name: 'Постоянное расписание' }).click();
  const friday = page.getByRole('group', { name: 'Пятница', exact: true });
  const saturday = page.getByRole('group', { name: 'Суббота', exact: true });
  for (const night of [friday, saturday]) {
    await night.getByLabel('Проводим вечеринку').check();
    await night.getByLabel('День открытия').selectOption('same');
    await night.getByLabel('Время открытия').fill('00:00');
    await night.getByLabel('День закрытия').selectOption('next');
    await night.getByLabel('Время закрытия').fill('12:00');
  }
  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 });
    await expectReadableNightControls(friday);
    await expectReadableNightControls(saturday);
  }

  const save = page.getByRole('button', { name: 'Сохранить расписание' });
  const confirm = page.getByRole('button', { name: 'Подтвердить расписание' });
  const conflict = page
    .getByRole('alert')
    .filter({ hasText: 'Вечеринки пересекаются' });
  const preview = page.locator('.cc-result li');
  const expectInterval = async (
    party: string,
    opens: string,
    closes: string,
  ) => {
    const times = preview
      .filter({ has: page.getByText(party, { exact: true }) })
      .locator('time');
    await expect(times).toHaveCount(2);
    expect(
      await times.evaluateAll((items) =>
        items.map((el) => Date.parse(el.getAttribute('datetime')!)),
      ),
    ).toEqual([Date.parse(opens), Date.parse(closes)]);
  };

  await save.click();
  await expectInterval(
    'Ночь пятницы · 27.11.2026',
    '2026-11-27T00:00Z',
    '2026-11-28T12:00Z',
  );
  await expectInterval(
    'Ночь субботы · 28.11.2026',
    '2026-11-28T00:00Z',
    '2026-11-29T12:00Z',
  );
  await expect.soft(confirm).toBeDisabled();
  await expect(conflict).toBeVisible();
  await expect(conflict).toContainText('27.11.2026');
  await expect(conflict).toContainText(
    '28.11.2026 с 00:00 до 12:00 по времени клуба',
  );
  await conflict.screenshot({
    path: test.info().outputPath('calendar-overlap-390.png'),
  });
  expect(writes).toEqual([]);

  await page
    .getByRole('button', { name: 'Вернуться к редактированию' })
    .click();
  for (const night of [friday, saturday]) {
    await night.getByLabel('День открытия').selectOption('next');
    await night.getByLabel('День закрытия').selectOption('same');
  }
  await save.click();
  await expect(conflict).toHaveCount(0);
  await expectInterval(
    'Ночь пятницы · 27.11.2026',
    '2026-11-28T00:00Z',
    '2026-11-28T12:00Z',
  );
  await expectInterval(
    'Ночь субботы · 28.11.2026',
    '2026-11-29T00:00Z',
    '2026-11-29T12:00Z',
  );
  await expect(confirm).toBeEnabled();
  expect(writes).toEqual([]);
  await confirm.click();
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]).toEqual({
    expectedVersion: '1',
    timezone: profile.timezone,
    reconciliationDate: '2026-11-25',
    weekly: [5, 6].map((day) => ({
      day,
      mode: 'open',
      open: expect.stringMatching(/^00:00(:00)?$/),
      close: expect.stringMatching(/^12:00(:00)?$/),
      startNextDay: true,
      nextDay: false,
    })),
    holidays: [],
    exceptions: [],
  });
});
