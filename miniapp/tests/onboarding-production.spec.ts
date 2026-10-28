import { expect, test } from '@playwright/test';

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
