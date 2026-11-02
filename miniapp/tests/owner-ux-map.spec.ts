import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const signed = new URLSearchParams({
  user: JSON.stringify({ id: 880062001, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const telegramHash =
  '#tgWebAppData=' +
  encodeURIComponent(signed) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';
const client = {
  id: '20',
  name: 'Организация A',
  active: true,
  activeMembers: 0,
  archived: false,
  archivedAt: null,
  version: '1',
  archiveBlockers: [],
  availableActions: ['archive'],
  allowedClubs: 2,
  createdClubs: 0,
  availableClubs: 2,
  limitVersion: '1',
  members: [],
  clubs: [],
  invitations: [],
};
const profile = {
  id: '100',
  name: 'Первый клуб',
  clientId: '20',
  clientName: 'Организация A',
  permissions: ['client.club.read'],
  availableActions: [],
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Улица 1',
  unit: '',
  version: '1',
  savedAt: '2026-11-02T12:00:00Z',
};

async function telegramEvent(page: Page, name: string, data = {}) {
  await page.evaluate(
    ({ name, data }) => {
      const telegram = window.Telegram as unknown as {
        WebView: { receiveEvent: (name: string, data: object) => void };
      };
      telegram.WebView.receiveEvent(name, data);
    },
    { name, data },
  );
}

test('distinct owner sections keep navigation inside mobile viewport through scroll and Telegram resize', async ({
  page,
}) => {
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    let json: unknown = { items: [], nextCursor: null };
    if (path.endsWith('/access')) json = { platformOwner: true };
    if (path.endsWith('/summary'))
      json = {
        clients: 50,
        archivedClients: 0,
        clubs: 50,
        activeMembers: 0,
        openInvitations: 0,
      };
    if (path.endsWith('/clubs/search'))
      json = {
        items: Array.from({ length: 50 }, (_, index) => ({
          id: String(100 + index),
          name: 'Клуб ' + index,
          clientId: '20',
          clientName: client.name,
          published: false,
          clientActive: true,
          clientArchived: false,
        })),
        nextCursor: null,
      };
    if (path.endsWith('/clients/search'))
      json = { items: [client], nextCursor: null };
    return route.fulfill({ json });
  });
  await page.goto('/app?mode=platform-owner' + telegramHash);
  await expect(page.getByText('Организации вне архива')).toBeVisible();
  await expect(page.getByRole('button', { name: /Настройки/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Клубы', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Клуб 0', exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/screen=clubs/);
  await page.evaluate(() => {
    document.documentElement.style.setProperty(
      '--tg-safe-area-inset-bottom',
      '34px',
    );
    document.documentElement.style.setProperty(
      '--tg-content-safe-area-inset-bottom',
      '12px',
    );
  });
  for (const [width, height] of [
    [390, 844],
    [320, 620],
    [430, 540],
  ]) {
    await page.setViewportSize({ width, height });
    await telegramEvent(page, 'viewport_changed', {
      height,
      is_state_stable: true,
      is_expanded: true,
    });
    await page.locator('.po-main').evaluate((node) => {
      node.scrollTop = node.scrollHeight;
    });
    await expect(
      page.getByRole('heading', { name: 'Клуб 49', exact: true }),
    ).toBeVisible();
    const nav = await page
      .getByRole('navigation', { name: 'Навигация платформы' })
      .boundingBox();
    expect(nav!.y + nav!.height).toBeLessThanOrEqual(height + 1);
    expect(nav!.y).toBeGreaterThan(0);
    const content = await page.locator('.po-main').boundingBox();
    expect(content!.y + content!.height).toBeLessThanOrEqual(nav!.y + 1);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await expect(
      page.getByRole('button', { name: 'Доступы', exact: true }),
    ).toBeInViewport();
    expect(
      await page
        .locator('.po-nav')
        .evaluate((node) => parseFloat(getComputedStyle(node).paddingBottom)),
    ).toBeGreaterThanOrEqual(58);
  }
  await page.screenshot({
    path: test.info().outputPath('owner-clubs-safe-area.png'),
  });
  await page.getByRole('button', { name: 'Доступы', exact: true }).click();
  await expect(page.getByLabel('Список организаций')).toBeVisible();
  await expect(page).toHaveURL(/screen=clients/);
  await page.goBack();
  await expect(page).toHaveURL(/screen=clubs/);
  await page.goForward();
  await expect(page).toHaveURL(/screen=clients/);
  await telegramEvent(page, 'back_button_pressed');
  await expect(page).toHaveURL(/screen=clubs/);
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Управление платформой', exact: true }),
  ).toBeVisible();
});

test('archive is reversible, filtered and explains revoked access', async ({
  page,
}) => {
  let archived = false;
  const commands: { path: string; body: unknown; key: string }[] = [];
  await page.route('**/api/**', (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/access'))
      return route.fulfill({ json: { platformOwner: true } });
    if (/\/(archive|restore)$/.test(path)) {
      commands.push({
        path,
        body: request.postDataJSON(),
        key: request.headers()['idempotency-key'],
      });
      archived = path.endsWith('/archive');
    }
    const current = {
      ...client,
      archived,
      version: archived ? '2' : commands.length ? '3' : '1',
      availableActions: [archived ? 'restore' : 'archive'],
    };
    if (path.endsWith('/clients/search'))
      return route.fulfill({
        json: {
          items:
            (request.postDataJSON().status === 'archived') === archived
              ? [current]
              : [],
          nextCursor: null,
        },
      });
    return route.fulfill({ json: current });
  });
  page.on('dialog', (dialog) => dialog.accept());
  await page.goto('/app?mode=platform-owner&screen=clients' + telegramHash);
  await page.getByRole('button', { name: /Организация A/ }).click();
  await expect(page.getByText('Активных пользователей: 0')).toBeVisible();
  await expect(
    page.getByText(/Отзыв доступа отключает пользователя/),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Архивировать организацию' }).click();
  await expect(
    page.getByRole('button', { name: 'Восстановить организацию' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(
    page.getByText('В этом списке организаций пока нет.'),
  ).toBeVisible();
  await page.getByLabel('Список организаций').selectOption('archived');
  await page.getByRole('button', { name: /Организация A/ }).click();
  await page.getByRole('button', { name: 'Восстановить организацию' }).click();
  await expect(
    page.getByRole('button', { name: 'Архивировать организацию' }),
  ).toBeVisible();
  expect(commands).toHaveLength(2);
  expect(commands.map((command) => command.body)).toEqual([
    { expectedVersion: '1' },
    { expectedVersion: '2' },
  ]);
  expect(commands.every((command) => command.key.length > 0)).toBe(true);
});

test('single club opens directly with organization path; multiple contexts keep selection', async ({
  page,
}) => {
  let multiple = false;
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/context'))
      return route.fulfill({
        json: {
          clients: [
            { ...client, clubs: [profile] },
            ...(multiple
              ? [{ ...client, id: '21', name: 'Организация B' }]
              : []),
          ],
        },
      });
    if (path.endsWith('/profile')) return route.fulfill({ json: profile });
    if (path.endsWith('/onboarding'))
      return route.fulfill({
        json: {
          ...client,
          permissions: ['client.application.manage'],
          clubs: [profile],
        },
      });
    return route.fulfill({ json: { items: [], nextCursor: null } });
  });
  await page.goto('/app?mode=client' + telegramHash);
  await expect(page.getByRole('heading', { name: profile.name })).toBeVisible();
  await expect(page).toHaveURL(/screen=club&resource=100/);
  await expect(page.getByText('Не опубликован', { exact: true })).toBeVisible();
  await expect(
    page.getByText('Бот не подключён', { exact: true }),
  ).toBeVisible();
  await page
    .getByRole('button', { name: 'Организация и другие заведения' })
    .click();
  await expect(
    page.getByRole('button', { name: 'Создать заведение', exact: true }),
  ).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: profile.name })).toBeVisible();
  await page.goForward();
  await expect(
    page.getByText('Организация · клиентское пространство'),
  ).toBeVisible();
  multiple = true;
  await page.goto('/app?mode=client' + telegramHash);
  await expect(
    page.getByRole('button', { name: /Организация B/ }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: profile.name })).toHaveCount(
    0,
  );
});
