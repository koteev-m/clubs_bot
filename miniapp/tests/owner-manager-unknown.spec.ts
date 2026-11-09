import { expect, test, type Page } from '@playwright/test';
import type { CalendarView } from '../src/modules/client/calendar';

const auth = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const entry =
  '/app?mode=client&screen=calendar&resource=42#' +
  new URLSearchParams({
    tgWebAppData: auth,
    tgWebAppVersion: '7.10',
    tgWebAppPlatform: 'ios',
  });
const endpoint = '/api/client/clubs/42/calendar';
const empty: CalendarView = {
  clubId: '42',
  name: 'Unknown recovery',
  timezone: 'Europe/London',
  version: '1',
  canWrite: true,
  weekly: [],
  holidays: [],
  exceptions: [],
  nights: [],
  invalidDates: [],
  reconciliation: { businessDate: '2027-08-01', storedDates: [] },
};
type Attempt = { body: string | null; key: string | undefined };
const recovery = (page: Page) =>
  page.getByRole('button', {
    name: /^(Проверить результат операции|Повторить ту же операцию)$/,
  });
async function startMaterialization(page: Page) {
  await page.goto(entry);
  await page
    .getByRole('button', { name: 'Сформировать ближайшие ночи', exact: true })
    .click();
  const choice = page.getByLabel('Выбор времени при переводе часов');
  if (await choice.count()) await choice.selectOption('first');
  await page
    .getByRole('button', { name: 'Подтвердить формирование', exact: true })
    .click();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toBeVisible();
}
test.use({ timezoneId: 'Pacific/Honolulu' });

test('unknown committed no-op resolves after club midnight without another mutation', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-08-01T22:59:00Z'));
  let current = structuredClone(empty);
  let reads = 0;
  const writes: Attempt[] = [];
  const resolutions: Attempt[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const attempt = {
      body: request.postData(),
      key: request.headers()['idempotency-key'],
    };
    if (path === endpoint + '/commands/resolve') {
      expect(request.method()).toBe('POST');
      resolutions.push(attempt);
      return route.fulfill({
        json: { status: 'completed', calendar: current },
      });
    }
    if (path === endpoint && request.method() === 'PATCH') {
      writes.push(attempt);
      return route.fulfill({ status: 503, json: { code: 'unavailable' } });
    }
    reads++;
    return route.fulfill({ json: current });
  });
  await startMaterialization(page);
  await page.clock.setFixedTime(new Date('2027-08-01T23:01:00Z'));
  current = {
    ...current,
    reconciliation: { businessDate: '2027-08-02', storedDates: [] },
  };
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect.poll(() => reads).toBe(2);
  await recovery(page).click();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText('Операция выполнена. Показано актуальное расписание.', { exact: true })).toBeVisible();
  expect(resolutions.length).toBe(1);
  expect(resolutions[0].body === writes[0].body).toBe(true);
  expect(resolutions[0].key === writes[0].key).toBe(true);
  expect(writes.length).toBe(1);
  await page.screenshot({
    path: test.info().outputPath('unknown-noop-midnight-resolved.png'),
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(page).not.toHaveURL(/screen=calendar/);
});

test('unknown retry does not reuse overlap consent in a London UTC London pre-commit race', async ({
  page,
}) => {
  await page.addInitScript(() => {
    const state = window as unknown as {
      __unknownRaceArmed: boolean;
      __unknownRaceObservation: {
        subtitle: string | null;
        choiceVisible: boolean;
        clicked: boolean;
      } | null;
    };
    state.__unknownRaceArmed = false;
    state.__unknownRaceObservation = null;
    const original = window.fetch;
    window.fetch = async (...args) => {
      const response = await original(...args);
      const json = response.json.bind(response);
      response.json = async () => {
        const data = await json();
        if (
          state.__unknownRaceArmed &&
          data.timezone === 'Europe/London' &&
          data.reconciliation
        ) {
          state.__unknownRaceArmed = false;
          const schedule = (remaining: number) =>
            queueMicrotask(() => {
              if (remaining) {
                schedule(remaining - 1);
                return;
              }
              const retry = [
                ...document.querySelectorAll<HTMLButtonElement>('button'),
              ].find((button) =>
                /^(Повторить исходную команду|Повторить ту же операцию)$/.test(
                  button.textContent ?? '',
                ),
              );
              state.__unknownRaceObservation = {
                subtitle:
                  document.querySelector('.po-page-title')?.textContent ??
                  null,
                choiceVisible: [...document.querySelectorAll('label')].some(
                  (label) =>
                    label.textContent?.includes(
                      'Выбор времени при переводе часов',
                    ) && Boolean(label.querySelector('select')),
                ),
                clicked: Boolean(retry),
              };
              retry?.click();
            });
          schedule(8);
        }
        return data;
      };
      return response;
    };
  });
  await page.clock.setFixedTime(new Date('2027-10-31T00:00:00Z'));
  const london: CalendarView = {
    ...empty,
    reconciliation: { businessDate: '2027-10-31', storedDates: [] },
    weekly: [
      {
        day: 6,
        mode: 'open',
        open: '00:30',
        close: '05:00',
        startNextDay: true,
        nextDay: false,
      },
    ],
  };
  let current = london;
  let reads = 0;
  const writes: Attempt[] = [];
  const resolutions: Attempt[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const attempt = {
      body: request.postData(),
      key: request.headers()['idempotency-key'],
    };
    if (path === endpoint + '/commands/resolve') {
      resolutions.push(attempt);
      return route.fulfill({
        json: { status: 'not_committed_at_snapshot', calendar: current },
      });
    }
    if (request.method() === 'PATCH') {
      writes.push(attempt);
      if (writes.length === 1)
        return route.fulfill({ status: 503, json: { code: 'unavailable' } });
      current = {
        ...current,
        version: '2',
        weekly: request.postDataJSON().weekly,
      };
      return route.fulfill({ json: current });
    }
    reads++;
    return route.fulfill({ json: current });
  });
  await page.goto(entry);
  await page
    .getByRole('tab', { name: 'Постоянное расписание', exact: true })
    .click();
  await page
    .getByRole('group', { name: 'Суббота', exact: true })
    .getByLabel('Время открытия', { exact: true })
    .fill('01:30');
  await page
    .getByRole('button', { name: 'Сохранить расписание', exact: true })
    .click();
  await page
    .getByLabel('Выбор времени при переводе часов')
    .selectOption('first');
  await page
    .getByRole('button', { name: 'Подтвердить расписание', exact: true })
    .click();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toBeVisible();
  const resolver = page.getByRole('button', {
    name: 'Проверить результат операции',
    exact: true,
  });
  if (await resolver.count()) {
    await resolver.click();
    await expect(
      page.getByRole('button', {
        name: 'Повторить исходную команду',
        exact: true,
      }),
    ).toBeVisible();
    const renewedChoice = page.getByLabel('Выбор времени при переводе часов');
    if (await renewedChoice.count())
      await renewedChoice.selectOption('first');
    expect(resolutions.length).toBe(1);
    expect(resolutions[0].body === writes[0].body).toBe(true);
    expect(resolutions[0].key === writes[0].key).toBe(true);
  }
  current = { ...london, timezone: 'UTC' };
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Unknown recovery · UTC', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByLabel('Выбор времени при переводе часов'),
  ).toHaveCount(0);
  current = london;
  await page.evaluate(() => {
    (
      window as unknown as { __unknownRaceArmed: boolean }
    ).__unknownRaceArmed = true;
    window.dispatchEvent(new Event('focus'));
  });
  await expect.poll(() => reads).toBe(3);
  await page.waitForFunction(
    () =>
      (window as unknown as { __unknownRaceObservation: unknown })
        .__unknownRaceObservation !== null,
  );
  await page.waitForLoadState('networkidle');
  const observation = await page.evaluate(
    () =>
      (
        window as unknown as {
          __unknownRaceObservation: {
            subtitle: string | null;
            choiceVisible: boolean;
            clicked: boolean;
          };
        }
      ).__unknownRaceObservation,
  );
  await test
    .info()
    .attach('aba-observation', {
      body: JSON.stringify({
        observation,
        writes: writes.length,
        resolutions: resolutions.length,
        sameBody: writes.length > 1 && writes[0].body === writes[1].body,
        sameKey: writes.length > 1 && writes[0].key === writes[1].key,
      }),
      contentType: 'application/json',
    });
  expect(observation.subtitle).toContain('UTC');
  expect(observation.choiceVisible).toBe(false);
  expect(observation.clicked).toBe(true);
  expect(writes.length).toBe(1);
  await page.screenshot({
    path: test.info().outputPath('unknown-aba-consent-fenced.png'),
    fullPage: true,
  });
  await page
    .getByRole('button', {
      name: 'Проверить результат операции',
      exact: true,
    })
    .click();
  await page
    .getByLabel('Выбор времени при переводе часов')
    .selectOption('first');
  await page
    .getByRole('button', { name: 'Повторить исходную команду', exact: true })
    .click();
  await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
  expect(writes.length).toBe(2);
  expect(writes[1].body === writes[0].body).toBe(true);
  expect(writes[1].key === writes[0].key).toBe(true);
});

test('indeterminate resolution stays read-only and absence requires an explicit original retry', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-08-01T00:00:00Z'));
  const writes: Attempt[] = [];
  const resolutions: Attempt[] = [];
  const current = structuredClone(empty);
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const attempt = {
      body: request.postData(),
      key: request.headers()['idempotency-key'],
    };
    if (new URL(request.url()).pathname === endpoint + '/commands/resolve') {
      resolutions.push(attempt);
      return route.fulfill({
        json:
          resolutions.length === 1
            ? { status: 'indeterminate' }
            : { status: 'not_committed_at_snapshot', calendar: current },
      });
    }
    if (request.method() === 'PATCH') {
      writes.push(attempt);
      if (writes.length === 1)
        return route.fulfill({ status: 503, json: { code: 'unavailable' } });
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: current });
  });
  await startMaterialization(page);
  const check = page.getByRole('button', {
    name: 'Проверить результат операции',
    exact: true,
  });
  await check.click();
  await expect(check).toBeEnabled();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', {
      name: 'Повторить исходную команду',
      exact: true,
    }),
  ).toHaveCount(0);
  expect(writes.length).toBe(1);
  await check.click();
  const retry = page.getByRole('button', {
    name: 'Повторить исходную команду',
    exact: true,
  });
  await expect(retry).toBeEnabled();
  expect(writes.length).toBe(1);
  expect(resolutions.length).toBe(2);
  for (const resolution of resolutions) {
    expect(resolution.body === writes[0].body).toBe(true);
    expect(resolution.key === writes[0].key).toBe(true);
  }
  await retry.click();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
  expect(writes.length).toBe(2);
  expect(writes[1].body === writes[0].body).toBe(true);
  expect(writes[1].key === writes[0].key).toBe(true);
});

test('temporary resolver failure retains the unknown intent and recovers with another read only', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-08-01T00:00:00Z'));
  const writes: Attempt[] = [];
  const resolutions: Attempt[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const attempt = {
      body: request.postData(),
      key: request.headers()['idempotency-key'],
    };
    if (new URL(request.url()).pathname === endpoint + '/commands/resolve') {
      resolutions.push(attempt);
      return resolutions.length === 1
        ? route.fulfill({ status: 503, json: { code: 'unavailable' } })
        : route.fulfill({ json: { status: 'completed', calendar: empty } });
    }
    if (request.method() === 'PATCH') {
      writes.push(attempt);
      return route.fulfill({ status: 503, json: { code: 'unavailable' } });
    }
    return route.fulfill({ json: empty });
  });
  await startMaterialization(page);
  const check = page.getByRole('button', {
    name: 'Проверить результат операции',
    exact: true,
  });
  await check.click();
  await expect.poll(() => resolutions.length).toBe(1);
  await expect(check).toBeEnabled();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toBeVisible();
  expect(writes.length).toBe(1);
  await check.click();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toHaveCount(0);
  expect(resolutions.length).toBe(2);
  expect(writes.length).toBe(1);
  for (const resolution of resolutions) {
    expect(resolution.body === writes[0].body).toBe(true);
    expect(resolution.key === writes[0].key).toBe(true);
  }
});

test('absence on an incompatible same-version timezone snapshot cannot authorize a write', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-10-31T00:00:00Z'));
  let current: CalendarView = {
    ...empty,
    timezone: 'UTC',
    reconciliation: { businessDate: '2027-10-31', storedDates: [] },
    weekly: [
      {
        day: 6,
        mode: 'open',
        open: '01:30',
        close: '05:00',
        startNextDay: true,
        nextDay: false,
      },
    ],
  };
  const writes: Attempt[] = [];
  const resolutions: Attempt[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const attempt = {
      body: request.postData(),
      key: request.headers()['idempotency-key'],
    };
    if (new URL(request.url()).pathname === endpoint + '/commands/resolve') {
      resolutions.push(attempt);
      return route.fulfill({
        json: { status: 'not_committed_at_snapshot', calendar: current },
      });
    }
    if (request.method() === 'PATCH') {
      writes.push(attempt);
      return writes.length === 1
        ? route.fulfill({ status: 503, json: { code: 'unavailable' } })
        : route.fulfill({ json: current });
    }
    return route.fulfill({ json: current });
  });
  await startMaterialization(page);
  current = { ...current, timezone: 'Europe/London' };
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Unknown recovery · Europe/London', { exact: true }),
  ).toBeVisible();
  await page
    .getByRole('button', {
      name: 'Проверить результат операции',
      exact: true,
    })
    .click();
  await expect.poll(() => resolutions.length).toBe(1);
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toBeVisible();
  const retry = page.getByRole('button', {
    name: 'Повторить исходную команду',
    exact: true,
  });
  await expect(retry).toBeVisible();
  await retry.click();
  await expect(page.getByText('Исходная команда не соответствует актуальному расписанию. Повтор остановлен; проверьте её результат ещё раз.', { exact: true })).toBeVisible();
  expect(writes.length).toBe(1);
  expect(resolutions[0].body === writes[0].body).toBe(true);
  expect(resolutions[0].key === writes[0].key).toBe(true);
  await page.screenshot({
    path: test.info().outputPath('unknown-incompatible-retry-blocked.png'),
    fullPage: true,
  });
});

test('an unknown retry conflict retains the original intent and requires fresh overlap consent after timezone recovery', async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date('2027-10-31T00:00:00Z'));
  const london: CalendarView = {
    ...empty,
    reconciliation: { businessDate: '2027-10-31', storedDates: [] },
    weekly: [
      {
        day: 6,
        mode: 'open',
        open: '00:30',
        close: '05:00',
        startNextDay: true,
        nextDay: false,
      },
    ],
  };
  let current = london;
  let effects = 0;
  const writes: Attempt[] = [];
  const resolutions: Attempt[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const attempt = {
      body: request.postData(),
      key: request.headers()['idempotency-key'],
    };
    if (path === endpoint + '/commands/resolve') {
      expect(request.method()).toBe('POST');
      resolutions.push(attempt);
      return route.fulfill({
        json: { status: 'not_committed_at_snapshot', calendar: current },
      });
    }
    if (path === endpoint && request.method() === 'PATCH') {
      writes.push(attempt);
      if (writes.length === 1)
        return route.fulfill({ status: 503, json: { code: 'unavailable' } });
      if (writes.length === 2) {
        current = { ...london, timezone: 'UTC' };
        return route.fulfill({
          status: 409,
          json: { code: 'version_conflict' },
        });
      }
      effects++;
      current = {
        ...current,
        version: '2',
        weekly: request.postDataJSON().weekly,
      };
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: current });
  });
  await page.goto(entry);
  await page
    .getByRole('tab', { name: 'Постоянное расписание', exact: true })
    .click();
  await page
    .getByRole('group', { name: 'Суббота', exact: true })
    .getByLabel('Время открытия', { exact: true })
    .fill('01:30');
  await page
    .getByRole('button', { name: 'Сохранить расписание', exact: true })
    .click();
  await page
    .getByLabel('Выбор времени при переводе часов')
    .selectOption('first');
  await page
    .getByRole('button', { name: 'Подтвердить расписание', exact: true })
    .click();
  const unknown = page.getByText('Результат операции пока неизвестен', {
    exact: true,
  });
  await expect(unknown).toBeVisible();
  const check = page.getByRole('button', {
    name: 'Проверить результат операции',
    exact: true,
  });
  const retry = page.getByRole('button', {
    name: 'Повторить исходную команду',
    exact: true,
  });
  await check.click();
  await expect(retry).toBeEnabled();
  expect(writes.length).toBe(1);
  await retry.click();
  await expect(check).toBeEnabled();
  await expect(unknown).toBeVisible();
  await page.waitForLoadState('networkidle');
  await expect(
    page.getByRole('button', {
      name: 'Перенести мои правки на актуальную версию',
      exact: true,
    }),
  ).toHaveCount(0);
  await expect(page.getByText('Сохранено', { exact: true })).toHaveCount(0);
  expect(writes.length).toBe(2);
  expect(effects).toBe(0);
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Unknown recovery · UTC', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByLabel('Выбор времени при переводе часов'),
  ).toHaveCount(0);
  current = london;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Unknown recovery · Europe/London', { exact: true }),
  ).toBeVisible();
  const choice = page.getByLabel('Выбор времени при переводе часов');
  await expect(choice).toHaveValue('');
  await check.click();
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect(
    page.getByText('Подтвердите время исходной команды заново перед повтором.', {
      exact: true,
    }),
  ).toBeVisible();
  await page.waitForLoadState('networkidle');
  expect(writes.length).toBe(2);
  expect(effects).toBe(0);
  await choice.selectOption('first');
  await retry.click();
  await expect(unknown).toHaveCount(0);
  await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  expect(writes.length).toBe(3);
  expect(resolutions.length).toBe(2);
  expect(effects).toBe(1);
  for (const attempt of [...writes, ...resolutions]) {
    expect(attempt.body === writes[0].body).toBe(true);
    expect(attempt.key === writes[0].key).toBe(true);
  }
  await page.screenshot({
    path: test.info().outputPath('unknown-retry-conflict-recovered.png'),
    fullPage: true,
  });
});

test('a late resolver receipt cannot restore revoked calendar data or complete the old UI', async ({
  page,
}) => {
  await page.addInitScript(() => {
    const NativeAbortController = window.AbortController;
    window.AbortController = class extends NativeAbortController {
      abort() {}
    };
  });
  await page.clock.setFixedTime(new Date('2027-08-01T00:00:00Z'));
  let allowed = true;
  let receipt: import('@playwright/test').Route | undefined;
  let writes = 0;
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    if (new URL(request.url()).pathname === endpoint + '/commands/resolve') {
      receipt = route;
      return;
    }
    if (request.method() === 'PATCH') {
      writes++;
      return route.fulfill({ status: 503, json: { code: 'unavailable' } });
    }
    return allowed
      ? route.fulfill({ json: empty })
      : route.fulfill({ status: 404, json: { code: 'not_found' } });
  });
  await startMaterialization(page);
  await page
    .getByRole('button', {
      name: 'Проверить результат операции',
      exact: true,
    })
    .click();
  await expect.poll(() => Boolean(receipt)).toBe(true);
  allowed = false;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText(/Доступ утрачен или запись недоступна/).first(),
  ).toBeVisible();
  await receipt!.fulfill({
    json: {
      status: 'completed',
      calendar: { ...empty, name: 'Old private receipt' },
    },
  });
  await page.waitForLoadState('networkidle');
  await expect(
    page.getByText('Old private receipt · Europe/London', { exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText('Сохранено', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Операция выполнена. Показано актуальное расписание.', { exact: true })).toHaveCount(0);
  await expect(
    page.getByText('Unknown recovery · Europe/London', { exact: true }),
  ).toHaveCount(0);
  expect(writes).toBe(1);
});
