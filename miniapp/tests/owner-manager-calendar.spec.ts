import { expect, test, type Route } from '@playwright/test';
const auth = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const entry =
  '/app?mode=client&screen=calendar&resource=42#tgWebAppData=' +
  encodeURIComponent(auth) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';
const weekly = {
  day: 6,
  mode: 'open',
  open: '01:30',
  close: '05:00',
  startNextDay: true,
  nextDay: false,
};
const view = (today: string) => ({
  clubId: '42',
  name: 'Calendar review',
  timezone: 'Europe/London',
  version: '1',
  canWrite: true,
  weekly: [weekly],
  holidays: [],
  exceptions: [] as (typeof weekly)[] & { date: string }[],
  nights: [],
  invalidDates: [],
  reconciliation: { businessDate: today, storedDates: [] as string[] },
});
const profile = {
  id: '42',
  clientId: '21',
  clientName: 'Tenant',
  name: 'Calendar review',
  description: '',
  timezone: 'Europe/London',
  version: '1',
  permissions: ['client.club.read', 'client.club.calendar.write'],
};
test.use({ timezoneId: 'Pacific/Honolulu' });
test('removed future exception requires a new overlap decision before any calendar write', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-08-01T00:00:00Z'));
  let current = {
    ...view('2027-08-01'),
    exceptions: [{ ...weekly, date: '2027-10-30', open: '00:30' }],
  };
  const writes: unknown[] = [];
  await page.route('**/api/**', async (route) => {
    if (new URL(route.request().url()).pathname.endsWith('/calendar')) {
      if (route.request().method() === 'PATCH') {
        writes.push(route.request().postDataJSON());
        current = { ...current, version: '2', exceptions: [] };
      }
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: profile });
  });
  await page.goto(entry);
  await page.getByRole('tab', { name: 'Особые даты' }).click();
  await page
    .getByRole('button', { name: 'Убрать правило на эту дату' })
    .click();
  await page
    .getByRole('button', { name: 'Сохранить расписание', exact: true })
    .click();
  const confirm = page.getByRole('button', {
    name: 'Подтвердить расписание',
    exact: true,
  });
  await expect(confirm).toBeDisabled();
  expect(writes).toHaveLength(0);
  await page
    .getByLabel('Выбор времени при переводе часов')
    .scrollIntoViewIfNeeded();
  await page.screenshot({
    path: test.info().outputPath('removed-exception-overlap.png'),
    fullPage: true,
  });
  await page
    .getByLabel('Выбор времени при переводе часов')
    .selectOption('first');
  await confirm.click();
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]).toMatchObject({
    exceptions: [{ date: '2027-10-30', mode: 'remove' }],
  });
});
for (const kind of ['hours', 'timezone', 'version'] as const)
  test(`new authorized ${kind} snapshot invalidates overlap consent`, async ({
    page,
  }) => {
    await page.clock.setFixedTime(new Date('2027-10-31T00:00:00Z'));
    let current = view('2027-10-31');
    let reads = 0;
    let held!: Route;
    const writes: unknown[] = [];
    await page.route('**/api/**', async (route) => {
      if (new URL(route.request().url()).pathname.endsWith('/calendar')) {
        if (route.request().method() === 'PATCH') {
          writes.push(route.request().postDataJSON());
          return route.fulfill({ json: current });
        }
        if (++reads > 1) {
          held = route;
          return;
        }
        return route.fulfill({ json: current });
      }
      return route.fulfill({ json: profile });
    });
    await page.goto(entry);
    await page
      .getByRole('button', { name: 'Сформировать ближайшие ночи', exact: true })
      .click();
    const choice = page.getByLabel('Выбор времени при переводе часов');
    const confirm = page.getByRole('button', {
      name: 'Подтвердить формирование',
      exact: true,
    });
    await choice.selectOption('first');
    await expect(confirm).toBeEnabled();
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect.poll(() => Boolean(held)).toBe(true);
    current = {
      ...current,
      version: '2',
      timezone: kind === 'timezone' ? 'Europe/Berlin' : current.timezone,
      weekly: [
        {
          ...weekly,
          open:
            kind === 'timezone'
              ? '02:45'
              : kind === 'hours'
                ? '01:45'
                : '01:30',
        },
      ],
    };
    await held.fulfill({ json: current });
    await expect(choice).toHaveValue('');
    await expect(confirm).toBeDisabled();
    expect(writes).toHaveLength(0);
    await choice.scrollIntoViewIfNeeded();
    await page.screenshot({
      path: test.info().outputPath('fresh-' + kind + '-requires-choice.png'),
      fullPage: true,
    });
    await choice.selectOption('first');
    await confirm.click();
    await expect.poll(() => writes.length).toBe(1);
    expect(writes[0]).toMatchObject({
      expectedVersion: '2',
      timezone: current.timezone,
    });
  });

test('a stored date hidden beyond the preview horizon participates in materialization DST validation', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-08-01T00:00:00Z'));
  const current = {
    ...view('2027-08-01'),
    reconciliation: { businessDate: '2027-08-01', storedDates: ['2027-10-30'] },
  };
  let writes = 0;
  await page.route('**/api/**', (route) => {
    if (route.request().method() === 'PATCH') writes++;
    return route.fulfill({
      json: new URL(route.request().url()).pathname.endsWith('/calendar')
        ? current
        : profile,
    });
  });
  await page.goto(entry);
  await page
    .getByRole('button', { name: 'Сформировать ближайшие ночи', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Подтвердить формирование', exact: true }),
  ).toBeDisabled();
  expect(writes).toBe(0);
  await expect(page.getByLabel('Выбор времени при переводе часов')).toHaveValue(
    '',
  );
});
test('removing a distant spring exception blocks the unsupported gap without a write or false success', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-01-01T00:00:00Z'));
  const current = {
    ...view('2027-01-01'),
    exceptions: [{ ...weekly, date: '2027-03-27', open: '00:30' }],
  };
  let writes = 0;
  await page.route('**/api/**', (route) => {
    if (route.request().method() === 'PATCH') writes++;
    return route.fulfill({
      json: new URL(route.request().url()).pathname.endsWith('/calendar')
        ? current
        : profile,
    });
  });
  await page.goto(entry);
  await page.getByRole('tab', { name: 'Особые даты' }).click();
  await page
    .getByRole('button', { name: 'Убрать правило на эту дату' })
    .click();
  await page
    .getByRole('button', { name: 'Сохранить расписание', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Подтвердить расписание', exact: true }),
  ).toBeDisabled();
  expect(writes).toBe(0);
  await expect(page.getByText('Сохранено', { exact: true })).toHaveCount(0);
  await page.getByText(/Такого местного времени нет/).scrollIntoViewIfNeeded();
  await page.screenshot({
    path: test.info().outputPath('removed-exception-gap-blocked.png'),
    fullPage: true,
  });
});
test('reapply after a version conflict never carries the old overlap consent into a new write', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-10-31T00:00:00Z'));
  let current = view('2027-10-31');
  const writes: Record<string, unknown>[] = [];
  await page.route('**/api/**', async (route) => {
    if (new URL(route.request().url()).pathname.endsWith('/calendar')) {
      if (route.request().method() === 'PATCH') {
        writes.push(route.request().postDataJSON());
        if (writes.length === 1) {
          current = {
            ...current,
            version: '2',
            weekly: [{ ...weekly, open: '01:45' }],
          };
          return route.fulfill({
            status: 409,
            json: { code: 'version_conflict' },
          });
        }
      }
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: profile });
  });
  await page.goto(entry);
  await page.getByRole('tab', { name: 'Постоянное расписание' }).click();
  await page
    .getByRole('group', { name: 'Суббота' })
    .getByLabel('Время закрытия')
    .fill('06:00');
  await page
    .getByRole('button', { name: 'Сохранить расписание', exact: true })
    .click();
  await page
    .getByLabel('Выбор времени при переводе часов')
    .selectOption('first');
  await page
    .getByRole('button', { name: 'Подтвердить расписание', exact: true })
    .click();
  const reapply = page.getByRole('button', {
    name: 'Перенести мои правки на актуальную версию',
  });
  await expect(reapply).toBeEnabled();
  await reapply.click();
  await page
    .getByRole('button', { name: 'Сохранить расписание', exact: true })
    .click();
  const confirm = page.getByRole('button', {
    name: 'Подтвердить расписание',
    exact: true,
  });
  await expect(confirm).toBeDisabled();
  expect(writes).toHaveLength(1);
  await page
    .getByLabel('Выбор времени при переводе часов')
    .selectOption('first');
  await confirm.click();
  await expect.poll(() => writes.length).toBe(2);
  expect(writes[1]).toMatchObject({
    expectedVersion: '2',
    reconciliationDate: '2027-10-31',
  });
});
test('dispatch checks the club day synchronously even before a render or foreground refresh', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-08-01T00:00:00Z'));
  let current = {
    ...view('2027-08-01'),
    weekly: [{ ...weekly, open: '00:00', close: '08:00', day: 5 }],
  };
  const writes: Record<string, unknown>[] = [];
  await page.route('**/api/**', (route) => {
    if (new URL(route.request().url()).pathname.endsWith('/calendar')) {
      if (route.request().method() === 'PATCH')
        writes.push(route.request().postDataJSON());
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: profile });
  });
  await page.goto(entry);
  await page
    .getByRole('button', { name: 'Сформировать ближайшие ночи', exact: true })
    .click();
  const confirm = page.getByRole('button', {
    name: 'Подтвердить формирование',
    exact: true,
  });
  await expect(confirm).toBeEnabled();
  await page.clock.setFixedTime(new Date('2027-08-02T00:00:00Z'));
  current = {
    ...current,
    reconciliation: { businessDate: '2027-08-02', storedDates: [] },
  };
  await confirm.click();
  expect(writes).toHaveLength(0);
  await expect(
    page.getByText(/Расписание обновилось. Проверьте новый план/),
  ).toBeVisible();
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]).toMatchObject({ reconciliationDate: '2027-08-02' });
});
