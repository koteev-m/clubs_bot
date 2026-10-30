import { expect, test } from '@playwright/test';
import type { ClubProfile } from '../src/modules/client/onboarding.types';

const token = 'a'.repeat(43);
const signed = new URLSearchParams({
  user: JSON.stringify({ id: 880062001, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
  start_param: 'ci_' + token,
}).toString();
const hash =
  '#tgWebAppData=' +
  encodeURIComponent(signed) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';
const invitation = {
  id: '10',
  clientId: '20',
  clientName: 'Synthetic Client',
  recipientLabel: 'Synthetic',
  version: '1',
  status: 'issued',
  expiresAt: '2026-11-04T12:00:00Z',
  candidate: null,
  verificationCode: null,
  availableActions: ['claim'],
  acceptanceMode: 'direct',
};
const clubProfile: ClubProfile = {
  id: '100',
  clientId: '20',
  clientName: 'Synthetic Client',
  name: 'Synthetic Venue',
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Synthetic City',
  address: 'Synthetic Street 1',
  unit: 'Test Unit',
  version: '1',
  savedAt: '2026-10-28T12:00:00Z',
  permissions: ['client.club.read', 'client.club.profile.write'],
  availableActions: ['update'],
};
const ordinaryData = new URLSearchParams(signed);
ordinaryData.delete('start_param');
const ordinaryHash =
  '#tgWebAppData=' +
  encodeURIComponent(ordinaryData.toString()) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';

test('mobile profile preserves timezone, refreshes the club name on Back and keeps organizations separate', async ({
  page,
}) => {
  let stored = {
    ...clubProfile,
    clientName: 'Микс',
    name: 'Бедолаги',
    city: 'Moscow',
    region: 'Moscow',
    unit: '',
  };
  const patches: Record<string, unknown>[] = [];
  const reads: string[] = [];
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    reads.push(path);
    if (path.endsWith('/clubs/100/profile')) {
      if (route.request().method() === 'PATCH') {
        const body = route.request().postDataJSON();
        patches.push(body);
        stored = {
          ...stored,
          name: body.name,
          description: body.description,
          timezone: body.timezone,
          version: '2',
        };
      }
      return route.fulfill({ json: stored });
    }
    if (path.endsWith('/context'))
      return route.fulfill({
        json: {
          clients: [
            { id: '20', name: 'Микс', clubs: [stored] },
            {
              id: '21',
              name: 'Другая организация',
              clubs: [{ id: '101', name: 'Другой клуб' }],
            },
          ],
        },
      });
    if (path.endsWith('/clients/20/onboarding'))
      return route.fulfill({
        json: {
          id: '20',
          name: 'Микс',
          permissions: [],
          clubs: [stored],
          allowedClubs: 1,
          createdClubs: 1,
          availableClubs: 0,
        },
      });
    if (path.endsWith('/clients/21/onboarding'))
      return route.fulfill({
        json: {
          id: '21',
          name: 'Другая организация',
          permissions: [],
          clubs: [{ id: '101', name: 'Другой клуб' }],
        },
      });
    return route.fulfill({ status: 404, json: { code: 'not_found' } });
  });
  await page.goto('/app?mode=client' + ordinaryHash);
  await page.getByRole('button', { name: /Микс/ }).click();
  await expect(
    page.getByText('Организация · клиентское пространство'),
  ).toBeVisible();
  await page.getByRole('button', { name: /Бедолаги/ }).click();
  await expect(page.getByText('Организация: Микс')).toBeVisible();
  await expect(page.getByText('Moscow', { exact: true })).toHaveCount(1);
  await expect(page.locator('dt', { hasText: 'Регион' })).toHaveCount(0);
  await expect(page.locator('dt', { hasText: 'Помещение' })).toHaveCount(0);
  await expect(page.getByText(/UTC\+03:00/)).toBeVisible();
  await expect(page.getByLabel('Часовой пояс IANA')).toBeHidden();
  await expect(
    page.getByText('Бот не подключён', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(/Night Concierge открывает доступ к платформе/),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: /Подключ/ })).toHaveCount(0);
  await page.getByLabel('Отображаемое название заведения').fill('Микс клуб');
  await page.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
  expect(patches).toEqual([
    {
      name: 'Микс клуб',
      description: '',
      timezone: 'Europe/Moscow',
      expectedVersion: '1',
    },
  ]);
  await page.screenshot({
    path: test.info().outputPath('profile-mobile-saved.png'),
    fullPage: true,
  });
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(page).toHaveURL(/screen=client-space&resource=20/);
  await expect(page.getByRole('button', { name: /Микс клуб/ })).toBeVisible();
  await expect(page.getByText('Бедолаги', { exact: true })).toHaveCount(0);
  await expect(
    page.getByText('Другая организация', { exact: true }),
  ).toHaveCount(0);
  expect(
    reads.filter((path) => path.endsWith('/clients/20/onboarding')).length,
  ).toBeGreaterThanOrEqual(2);
  expect(reads.some((path) => path.endsWith('/clients/21/onboarding'))).toBe(
    false,
  );
  await page.getByRole('button', { name: /Микс клуб/ }).click();
  await expect(page.getByLabel('Отображаемое название заведения')).toHaveValue(
    'Микс клуб',
  );
  const nativeBack = () =>
    page.evaluate(() => {
      const telegram = window as unknown as {
        Telegram: { WebView: { receiveEvent: (event: string) => void } };
      };
      telegram.Telegram.WebView.receiveEvent('back_button_pressed');
    });
  await page.getByLabel('Описание клуба').fill('Несохранённое описание');
  page.once('dialog', (dialog) => dialog.dismiss());
  await nativeBack();
  await expect(page.getByLabel('Описание клуба')).toHaveValue(
    'Несохранённое описание',
  );
  await page.getByLabel('Описание клуба').fill('');
  await nativeBack();
  await expect(page.getByRole('button', { name: /Микс клуб/ })).toBeVisible();
  await page.getByRole('button', { name: /Микс клуб/ }).click();
  await page.goBack();
  await expect(page.getByRole('button', { name: /Микс клуб/ })).toBeVisible();
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await page.getByRole('button', { name: /Другая организация/ }).click();
  await expect(page.getByRole('button', { name: /Другой клуб/ })).toBeVisible();
  await expect(page.getByText('Микс клуб', { exact: true })).toHaveCount(0);
});

test('direct creation keeps its pending intent through Back Forward, opens saved private profile and respects capacity on reentry', async ({
  page,
}) => {
  let created = false;
  const creates: { key: string; body: string | null }[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/clients/20/clubs')) {
      creates.push({
        key: request.headers()['idempotency-key'],
        body: request.postData(),
      });
      created = true;
      if (creates.length === 1) return route.abort();
      return route.fulfill({ json: clubProfile });
    }
    if (path.endsWith('/profile')) return route.fulfill({ json: clubProfile });
    if (path.endsWith('/context'))
      return route.fulfill({
        json: {
          clients: [
            {
              id: '20',
              name: 'Synthetic Client',
              clubs: created ? [clubProfile] : [],
            },
          ],
        },
      });
    if (path.endsWith('/onboarding'))
      return route.fulfill({
        json: {
          id: '20',
          name: 'Synthetic Client',
          permissions: ['client.application.manage'],
          clubs: created ? [clubProfile] : [],
          allowedClubs: 1,
          createdClubs: created ? 1 : 0,
          availableClubs: created ? 0 : 1,
        },
      });
    if (path.endsWith('/locations'))
      return route.fulfill({
        json: { countries: [{ code: 'RU', name: 'Россия' }] },
      });
    if (path.endsWith('/locations/search'))
      return route.fulfill({
        json: {
          cities: [
            {
              id: '524901',
              countryCode: 'RU',
              name: 'Москва',
              region: 'Москва',
              timezone: 'Europe/Moscow',
            },
          ],
          hasMore: false,
        },
      });
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto('/app?mode=client' + ordinaryHash);
  await page.getByRole('button', { name: 'Создать заведение' }).click();
  await page.getByLabel('Название заведения').fill('Synthetic Venue');
  await page.getByLabel('Страна', { exact: true }).selectOption('RU');
  await page.getByLabel('Город', { exact: true }).fill('Москва');
  await page.getByLabel('Город и регион').selectOption('524901');
  await page.getByLabel('Полный адрес').fill('Synthetic Street 1');
  await expect(page.getByLabel('Часовой пояс IANA')).toHaveCount(0);
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await page.getByRole('button', { name: 'Создать заведение' }).click();
  await expect(
    page.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await page.goBack();
  await expect(page.getByText('Можно добавить: 0')).toBeVisible();
  await page.goForward();
  await expect(page.getByLabel('Название заведения')).toHaveValue(
    'Synthetic Venue',
  );
  await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await page.getByRole('button', { name: 'Повторить ту же операцию' }).click();
  await expect(page.getByText('Непубличный', { exact: true })).toBeVisible();
  await expect(
    page.getByText('Бот не подключён', { exact: true }),
  ).toBeVisible();
  expect(creates).toHaveLength(2);
  expect(creates[0]).toEqual(creates[1]);
  expect(JSON.parse(creates[0].body!)).toEqual({
    venueName: 'Synthetic Venue',
    countryCode: 'RU',
    cityId: '524901',
    address: 'Synthetic Street 1',
  });
  await page.goto('/app?mode=client' + ordinaryHash);
  await expect(
    page.getByRole('button', { name: 'Создать заведение' }),
  ).toBeDisabled();
  await page.getByRole('button', { name: /Synthetic Venue/ }).click();
  await expect(page).toHaveURL(/screen=club&resource=100/);
  await expect(
    page.getByText('Synthetic Street 1', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: test.info().outputPath('simplified-private-club.png'),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test('private profile validation, version conflict, access loss and foreground clearing are safe', async ({
  page,
}) => {
  let conflict = false;
  let revoked = false;
  let patches = 0;
  await page.route('**/api/**', (route) => {
    if (revoked)
      return route.fulfill({ status: 404, json: { code: 'not_found' } });
    if (route.request().method() === 'PATCH') {
      patches++;
      conflict = true;
      return route.fulfill({
        status: 409,
        json: { code: 'version_conflict' },
      });
    }
    return route.fulfill({
      json: conflict
        ? { ...clubProfile, name: 'Concurrent profile', version: '2' }
        : clubProfile,
    });
  });
  await page.goto('/app?mode=client&screen=club&resource=100' + ordinaryHash);
  await page.getByText('Исправить часовой пояс', { exact: true }).click();
  await page.getByLabel('Часовой пояс IANA').fill('Unknown/Synthetic');
  await page.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(page.getByText(/Укажите часовой пояс IANA/)).toBeVisible();
  expect(patches).toBe(0);
  await page.getByLabel('Часовой пояс IANA').fill('Europe/Paris');
  await page.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(page.getByText(/Запись уже изменена/)).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Concurrent profile', exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel('Часовой пояс IANA')).toHaveValue(
    'Europe/Paris',
  );
  await expect(
    page.getByRole('button', { name: 'Сохранить профиль' }),
  ).toBeDisabled();
  page.once('dialog', (dialog) => dialog.accept());
  await page
    .getByRole('button', { name: 'Загрузить актуальный профиль' })
    .click();
  await expect(page.getByLabel('Часовой пояс IANA')).toHaveValue(
    'Europe/Moscow',
  );
  revoked = true;
  await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await expect(page.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(page.getByText('Synthetic Street 1')).toHaveCount(0);
  await expect(page.getByLabel('Отображаемое название заведения')).toHaveCount(
    0,
  );
  expect(patches).toBe(1);
});

test('production hash-only launch bridges before guest requests and omits launch secrets from optional storage', async ({
  page,
}) => {
  const requests: string[] = [];
  await page.route('**/api/**', (route) => {
    requests.push(new URL(route.request().url()).pathname);
    return route.fulfill({ json: invitation });
  });
  await page.goto('/app' + hash);
  await expect(
    page.getByRole('heading', { name: 'Приглашение клиента' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Принять приглашение' }),
  ).toBeEnabled();
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  expect(
    requests.every((path) => path === '/api/client/invitations/resolve'),
  ).toBe(true);
  expect(page.url()).not.toContain(token);
  expect(
    await page.evaluate(() => sessionStorage.getItem('__telegram__initParams')),
  ).not.toContain('tgWebAppData');
  expect(
    await page.evaluate(() =>
      JSON.stringify({ ...sessionStorage, ...localStorage }),
    ),
  ).not.toContain(token);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test('mutation lost response, safe retry, version conflict and access loss remain actionable in packaged UI', async ({
  page,
}) => {
  let calls = 0;
  const mutations: { key: string; body: string | null }[] = [];
  let current = invitation;
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    if (request.url().endsWith('/claim')) {
      mutations.push({
        key: request.headers()['idempotency-key'],
        body: request.postData(),
      });
      calls++;
      if (calls === 1) return route.abort();
      if (calls === 2)
        return route.fulfill({
          status: 409,
          json: { code: 'version_conflict' },
        });
      return route.fulfill({ status: 404, json: { code: 'not_found' } });
    }
    return route.fulfill({ json: current });
  });
  await page.goto('/app?mode=client' + hash);
  await page.getByRole('button', { name: 'Принять приглашение' }).click();
  await expect(
    page.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Повторить ту же операцию' }).click();
  await expect(page.getByText(/Запись уже изменена/)).toBeVisible();
  expect(mutations[0]).toEqual(mutations[1]);
  current = { ...current, version: '2' };
  await page.getByRole('button', { name: 'Обновить данные' }).click();
  await page.getByRole('button', { name: 'Принять приглашение' }).click();
  await expect(page.getByText(/Доступ утрачен/)).toBeVisible();
});

test('platform access refusal cannot be bypassed in production by OWNER query or persisted role', async ({
  page,
}) => {
  await page.addInitScript(() => localStorage.setItem('platformOwner', 'true'));
  const requests: string[] = [];
  await page.route('**/api/**', (route) => {
    requests.push(new URL(route.request().url()).pathname);
    return route.fulfill({ status: 403, json: { code: 'forbidden' } });
  });
  await page.goto('/app?mode=platform-owner&role=OWNER&scope=GLOBAL' + hash);
  await expect(
    page.getByRole('heading', {
      name: 'Нет доступа к управлению платформой',
    }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Пригласить клиента' }),
  ).toHaveCount(0);
  expect(requests.every((path) => path === '/api/platform/access')).toBe(true);
});

test('browser Back Forward retains creation fields without saving a draft', async ({
  page,
}) => {
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/context'))
      return route.fulfill({
        json: {
          clients: [{ id: '20', name: 'Synthetic Client', clubs: [] }],
        },
      });
    if (path.endsWith('/onboarding'))
      return route.fulfill({
        json: {
          id: '20',
          name: 'Synthetic Client',
          permissions: ['client.application.manage'],
          clubs: [],
          allowedClubs: 1,
          createdClubs: 0,
          availableClubs: 1,
        },
      });
    if (path.endsWith('/locations'))
      return route.fulfill({
        json: { countries: [{ code: 'RU', name: 'Россия' }] },
      });
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto('/app?mode=client' + ordinaryHash);
  await page.getByRole('button', { name: 'Создать заведение' }).click();
  await page.getByLabel('Название заведения').fill('Unsaved synthetic venue');
  await page.goBack();
  await expect(
    page.getByRole('heading', { name: 'Synthetic Client' }),
  ).toBeVisible();
  await page.goForward();
  await expect(page.getByLabel('Название заведения')).toHaveValue(
    'Unsaved synthetic venue',
  );
});

test('owner invitation draft and pending intent survive Back Forward without retry scrolling to the top', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 520 });
  const commands: { key: string; body: string | null }[] = [];
  let releaseReplay!: () => void;
  const replayGate = new Promise<void>((resolve) => {
    releaseReplay = resolve;
  });
  let rechecking = false;
  let releaseAccess!: () => void;
  const accessGate = new Promise<void>((resolve) => {
    releaseAccess = resolve;
  });
  await page.route('**/api/**', async (route) => {
    if (route.request().url().endsWith('/api/platform/access')) {
      if (rechecking) await accessGate;
      return route.fulfill({ json: { platformOwner: true } });
    }
    if (
      route.request().url().endsWith('/api/platform/onboarding/invitations')
    ) {
      commands.push({
        key: route.request().headers()['idempotency-key'],
        body: route.request().postData(),
      });
      if (commands.length === 1) return route.abort();
      await replayGate;
      return route.fulfill({
        json: { ...invitation, availableActions: ['reissue', 'revoke'] },
      });
    }
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto('/app?mode=platform-owner' + ordinaryHash);
  await page.getByRole('button', { name: 'Пригласить клиента' }).click();
  await page.getByLabel('Название клиента или компании').fill('MIX Group');
  await page.getByLabel('Имя получателя').fill('Иван Петров');
  await expect(
    page.getByText(
      'Так клиент будет называться в вашей платформе. Это не название конкретного клуба',
    ),
  ).toBeVisible();
  await expect(
    page.getByText(
      'Имя для вашего удобства. Telegram ID или ссылка на профиль не нужны',
    ),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(
    page.getByRole('heading', {
      name: 'Управление платформой',
      exact: true,
    }),
  ).toBeVisible();
  await page.goForward();
  await expect(page.getByLabel('Название клиента или компании')).toHaveValue(
    'MIX Group',
  );
  await expect(page.getByLabel('Имя получателя')).toHaveValue('Иван Петров');
  await page.getByRole('button', { name: 'Создать ссылку' }).click();
  await expect(
    page.getByText('Ответ сервера не получен, результат нужно проверить.'),
  ).toBeVisible();
  // Foreground rechecks authority before showing private UI, while retaining the
  // original uncertain command for the same signed Telegram session.
  rechecking = true;
  const recheckRequest = page.waitForRequest((request) =>
    request.url().endsWith('/api/platform/access'),
  );
  await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await recheckRequest;
  await expect(page.getByText('Проверяем доступ…')).toBeVisible();
  await expect(page.getByLabel('Название клиента или компании')).toBeHidden();
  await expect(
    page.getByRole('button', { name: 'Проверить результат' }),
  ).toBeHidden();
  releaseAccess();
  await expect(page.getByLabel('Название клиента или компании')).toHaveValue(
    'MIX Group',
  );
  await expect(page.getByLabel('Имя получателя')).toHaveValue('Иван Петров');
  await expect(
    page.getByText('Ответ сервера не получен, результат нужно проверить.'),
  ).toBeVisible();
  expect(commands).toHaveLength(1);
  await page.goBack();
  await expect(
    page.getByRole('heading', {
      name: 'Управление платформой',
      exact: true,
    }),
  ).toBeVisible();
  await page.goForward();
  await expect(page.getByLabel('Название клиента или компании')).toHaveValue(
    'MIX Group',
  );
  await expect(
    page.getByRole('button', { name: 'Создать ссылку' }),
  ).toBeDisabled();
  const retry = page.getByRole('button', { name: 'Проверить результат' });
  await retry.scrollIntoViewIfNeeded();
  const beforeRetry = await page.evaluate(() => window.scrollY);
  expect(beforeRetry).toBeGreaterThan(0);
  await retry.click();
  await expect(page.getByText('Создаём приглашение…')).toBeVisible();
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  releaseReplay();
  await expect(
    page.getByRole('heading', { name: 'Ссылка не получена' }),
  ).toBeVisible();
  expect(commands).toHaveLength(2);
  expect(commands[0]).toEqual(commands[1]);
  await expect(
    page.getByRole('heading', { name: 'Приглашение создано' }),
  ).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: 'Открыть приглашение' }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  const storage = await page.evaluate(() =>
    JSON.stringify({ ...sessionStorage, ...localStorage }),
  );
  expect(storage).not.toContain('MIX Group');
  expect(storage).not.toContain('Иван Петров');
  expect(storage).not.toContain(commands[0].key);
  await page.screenshot({
    path: test.info().outputPath('invitation-safe-recovery-mobile.png'),
    fullPage: true,
  });
});

test('Main Mini App resolves owner and client cabinets using server authority without a guest request', async ({
  page,
}) => {
  let owner = true;
  const requested: string[] = [];
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    requested.push(path);
    if (path === '/api/platform/access')
      return owner
        ? route.fulfill({ json: { platformOwner: true } })
        : route.fulfill({ status: 403, json: { code: 'forbidden' } });
    if (path.endsWith('/context'))
      return route.fulfill({
        json: {
          clients: [{ id: '20', name: 'Synthetic Client', clubs: [] }],
        },
      });
    if (path.endsWith('/onboarding'))
      return route.fulfill({
        json: {
          id: '20',
          name: 'Synthetic Client',
          clubs: [],
          permissions: ['client.application.manage'],
          allowedClubs: 1,
          createdClubs: 0,
          availableClubs: 1,
        },
      });
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto('/app' + ordinaryHash);
  await expect(
    page.getByRole('heading', { name: 'Управление платформой', exact: true }),
  ).toBeVisible();
  owner = false;
  await page.goto('/app' + ordinaryHash);
  await expect(
    page.getByRole('heading', { name: 'Synthetic Client', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Создать заведение' }),
  ).toBeEnabled();
  expect(
    requested.every(
      (path) =>
        path.startsWith('/api/platform/') || path.startsWith('/api/client/'),
    ),
  ).toBe(true);
});

test('Main Mini App transport failure stays actionable without guest redirect', async ({
  page,
}) => {
  const paths: string[] = [];
  await page.route('**/api/**', (route) => {
    paths.push(new URL(route.request().url()).pathname);
    return route.fulfill({ status: 503, json: { code: 'unavailable' } });
  });
  await page.goto('/app' + ordinaryHash);
  await expect(
    page.getByRole('button', { name: 'Повторить проверку' }),
  ).toBeVisible();
  expect(paths.every((path) => path === '/api/platform/access')).toBe(true);
});
