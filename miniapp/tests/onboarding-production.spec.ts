import { expect, test } from '@playwright/test';
import type {
  ClubApplication,
  ClubProfile,
} from '../src/modules/client/onboarding.types';

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
const approvedApplication: ClubApplication = {
  id: '30',
  clientId: '20',
  clientName: 'Synthetic Client',
  authorUserId: '90',
  status: 'approved',
  version: '4',
  venueName: 'Synthetic Venue',
  countryCode: 'RU',
  city: 'Synthetic City',
  address: 'Synthetic Street 1',
  unit: 'Test Unit',
  timezone: 'Europe/Moscow',
  comment: '',
  previousApplicationId: null,
  createdClubId: null,
  submittedAt: '2026-10-28T10:00:00Z',
  decisionReason: null,
  history: [],
  availableActions: ['createClub'],
};
const ordinaryData = new URLSearchParams(signed);
ordinaryData.delete('start_param');
const ordinaryHash =
  '#tgWebAppData=' +
  encodeURIComponent(ordinaryData.toString()) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';

test('private club creation survives a lost response, saves a profile and reopens the same club from its client', async ({
  page,
}) => {
  let application = { ...approvedApplication };
  let profile = { ...clubProfile };
  const creates: { key: string; body: string | null }[] = [];
  const patches: object[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/applications/30/club')) {
      creates.push({
        key: request.headers()['idempotency-key'],
        body: request.postData(),
      });
      application = {
        ...application,
        createdClubId: profile.id,
        availableActions: ['openClub'],
      };
      if (creates.length === 1) return route.abort();
      return route.fulfill({ json: profile });
    }
    if (path.endsWith('/profile')) {
      if (request.method() === 'PATCH') {
        const fields = request.postDataJSON();
        patches.push(fields);
        profile = {
          ...profile,
          name: fields.name,
          description: fields.description,
          timezone: fields.timezone,
          version: '2',
          savedAt: '2026-10-28T14:00:00Z',
        };
      }
      return route.fulfill({ json: profile });
    }
    if (path.endsWith('/context'))
      return route.fulfill({
        json: {
          clients: [
            {
              id: '20',
              name: 'Synthetic Client',
              clubs: application.createdClubId ? [profile] : [],
            },
            { id: '21', name: 'Independent Client', clubs: [] },
          ],
        },
      });
    if (path.endsWith('/onboarding'))
      return route.fulfill({
        json: {
          id: '20',
          name: 'Synthetic Client',
          permissions: ['client.application.manage'],
          clubs: application.createdClubId ? [profile] : [],
        },
      });
    if (path.endsWith('/search'))
      return route.fulfill({
        json: { items: [application], nextCursor: null },
      });
    return route.fulfill({ json: application });
  });
  await page.goto('/app?mode=client' + ordinaryHash);
  await page.getByRole('button', { name: /Synthetic Client/ }).click();
  await page.getByRole('button', { name: /Synthetic Venue/ }).click();
  await page.getByRole('button', { name: 'Создать клуб' }).click();
  await expect(
    page.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Создать клуб' }),
  ).toBeDisabled();
  await page.getByRole('button', { name: 'Повторить ту же операцию' }).click();
  await expect(page.getByText('Непубличный', { exact: true })).toBeVisible();
  await expect(
    page.getByText('Бот не подключён', { exact: true }),
  ).toBeVisible();
  expect(creates).toHaveLength(2);
  expect(creates[0]).toEqual(creates[1]);
  await page.getByLabel('Отображаемое название').fill('Saved Synthetic Club');
  await page.getByLabel('Описание клуба').fill('Synthetic profile description');
  await page.getByLabel('Часовой пояс').fill('Europe/Paris');
  await page.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
  expect(patches).toEqual([
    {
      name: 'Saved Synthetic Club',
      description: 'Synthetic profile description',
      timezone: 'Europe/Paris',
      expectedVersion: '1',
    },
  ]);
  await expect(
    page.getByText('Synthetic Street 1', { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel('Адрес', { exact: true })).toHaveCount(0);
  await page.screenshot({
    path: test.info().outputPath('private-club-profile.png'),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.goto('/app?mode=client' + ordinaryHash);
  await page.getByRole('button', { name: /Synthetic Client/ }).click();
  await expect(page.getByText('Independent Client')).toHaveCount(0);
  await page.getByRole('button', { name: /Saved Synthetic Club/ }).click();
  await expect(page).toHaveURL(/screen=club&resource=100/);
  await expect(page.getByLabel('Описание клуба')).toHaveValue(
    'Synthetic profile description',
  );
  await page.goto(
    '/app?mode=client&screen=application&resource=30' + ordinaryHash,
  );
  await expect(page.getByRole('button', { name: 'Создать клуб' })).toHaveCount(
    0,
  );
  await page.getByRole('button', { name: 'Открыть клуб' }).click();
  await expect(page.getByLabel('Отображаемое название')).toHaveValue(
    'Saved Synthetic Club',
  );
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
      return route.fulfill({ status: 409, json: { code: 'version_conflict' } });
    }
    return route.fulfill({
      json: conflict
        ? { ...clubProfile, name: 'Concurrent profile', version: '2' }
        : clubProfile,
    });
  });
  await page.goto('/app?mode=client&screen=club&resource=100' + ordinaryHash);
  await page.getByLabel('Часовой пояс').fill('Unknown/Synthetic');
  await page.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(page.getByText(/Укажите часовой пояс IANA/)).toBeVisible();
  expect(patches).toBe(0);
  await page.getByLabel('Часовой пояс').fill('Europe/Paris');
  await page.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(page.getByText(/Запись уже изменена/)).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Concurrent profile', exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel('Часовой пояс')).toHaveValue('Europe/Paris');
  await expect(
    page.getByRole('button', { name: 'Сохранить профиль' }),
  ).toBeDisabled();
  page.once('dialog', (dialog) => dialog.accept());
  await page
    .getByRole('button', { name: 'Загрузить актуальный профиль' })
    .click();
  await expect(page.getByLabel('Часовой пояс')).toHaveValue('Europe/Moscow');
  revoked = true;
  await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await expect(page.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(page.getByText('Synthetic Street 1')).toHaveCount(0);
  await expect(page.getByLabel('Отображаемое название')).toHaveCount(0);
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
    page.getByRole('button', { name: 'Подтвердить аккаунт' }),
  ).toBeDisabled();
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
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Подтвердить аккаунт' }).click();
  await expect(
    page.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Повторить ту же операцию' }).click();
  await expect(page.getByText(/Запись уже изменена/)).toBeVisible();
  expect(mutations[0]).toEqual(mutations[1]);
  current = { ...current, version: '2' };
  await page.getByRole('button', { name: 'Обновить данные' }).click();
  await page.getByRole('button', { name: 'Подтвердить аккаунт' }).click();
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
    page.getByRole('heading', { name: 'Нет доступа к управлению платформой' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Пригласить клиента' }),
  ).toHaveCount(0);
  expect(requests.every((path) => path === '/api/platform/access')).toBe(true);
});

test('browser Back asks before discarding an unsaved application and cancel keeps the input', async ({
  page,
}) => {
  const data = new URLSearchParams(signed);
  data.delete('start_param');
  const ordinaryHash =
    '#tgWebAppData=' +
    encodeURIComponent(data.toString()) +
    '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/context'))
      return route.fulfill({
        json: { clients: [{ id: '20', name: 'Synthetic Client', clubs: [] }] },
      });
    if (path.endsWith('/onboarding'))
      return route.fulfill({
        json: {
          id: '20',
          name: 'Synthetic Client',
          permissions: ['client.application.manage'],
          clubs: [],
        },
      });
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto('/app?mode=client' + ordinaryHash);
  await page.getByRole('button', { name: /Synthetic Client/ }).click();
  await page.getByRole('button', { name: 'Подключить заведение' }).click();
  await page.getByLabel('Название заведения').fill('Unsaved synthetic venue');
  page.once('dialog', (dialog) => dialog.dismiss());
  await page.goBack();
  await expect(page.getByLabel('Название заведения')).toHaveValue(
    'Unsaved synthetic venue',
  );
  page.once('dialog', (dialog) => dialog.accept());
  await page.goBack();
  await expect(
    page.getByRole('heading', { name: 'Synthetic Client' }),
  ).toBeVisible();
  await expect(page.getByLabel('Название заведения')).toHaveCount(0);
});
