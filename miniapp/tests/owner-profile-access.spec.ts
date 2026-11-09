import { expect, test } from '@playwright/test';
import { telegramTheme } from './telegram-theme';

const credential = new URLSearchParams({
  user: JSON.stringify({ id: 880062041, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const profile = {
  id: '100',
  clientId: '20',
  clientName: 'Организация A',
  name: 'Клуб A1',
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Улица 1',
  unit: '',
  version: '3',
  savedAt: '2026-11-08T00:00:00Z',
  permissions: [
    'client.club.read',
    'client.club.profile.write',
    'client.club.finance.read',
  ],
  availableActions: ['update'],
};

for (const theme of ['light', 'dark'] as const) {
  test(`profile retains the same pending command through internal, browser and Telegram Back in ${theme}`, async ({
    page,
  }) => {
    await page.emulateMedia({ colorScheme: theme });
    const writes: Array<{ key: string; body: string | null }> = [];
    let saved = profile;
    await page.route('**/api/**', async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path.endsWith('/context'))
        return route.fulfill({
          json: {
            clients: [
              {
                id: '20',
                name: 'Организация A',
                clubs: [
                  { id: '100', name: 'Клуб A1' },
                  { id: '101', name: 'Клуб A2' },
                ],
              },
            ],
          },
        });
      if (path.endsWith('/onboarding'))
        return route.fulfill({
          json: {
            id: '20',
            name: 'Организация A',
            permissions: [],
            clubs: [
              { id: '100', name: 'Клуб A1' },
              { id: '101', name: 'Клуб A2' },
            ],
          },
        });
      if (path.endsWith('/profile')) {
        if (request.method() === 'PATCH') {
          writes.push({
            key: request.headers()['idempotency-key'],
            body: request.postData(),
          });
          saved = {
            ...profile,
            description: 'Сохранённое описание',
            version: '4',
          };
          if (writes.length === 1) return route.abort('failed');
        }
        return route.fulfill({ json: saved });
      }
      return route.fulfill({
        status: 503,
        json: { code: 'temporarily_unavailable' },
      });
    });
    await page.goto(
      '/app?mode=client#' +
        new URLSearchParams({
          tgWebAppData: credential,
          tgWebAppVersion: '7.10',
          tgWebAppPlatform: 'ios',
        }),
    );
    await page.getByRole('button', { name: 'Клуб A1 Открыть клуб' }).click();
    await page
      .getByRole('button', {
        name: /Перейти к настройкам|Открыть настройки клуба/,
      })
      .click();
    await page.getByRole('button', { name: /^Профиль/ }).click();
    await telegramTheme(page, theme);
    await page
      .getByRole('textbox', { name: 'Описание клуба', exact: true })
      .fill('Сохранённое описание');
    await page
      .getByRole('button', { name: 'Сохранить профиль', exact: true })
      .click();
    await expect(
      page.getByText('Результат операции пока неизвестен', { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Финансовые настройки', exact: true }),
    ).toBeDisabled();
    await expect(
      page.getByRole('button', {
        name: 'Настройки клуба',
        exact: true,
      }),
    ).toBeDisabled();
    await page.evaluate(async () => {
      (
        window.Telegram as unknown as {
          WebView: { receiveEvent: (name: string, data: object) => void };
        }
      ).WebView.receiveEvent('back_button_pressed', {});
      await new Promise<void>((done) => {
        let movements = 0;
        const restored = () => {
          if (++movements !== 2) return;
          window.removeEventListener('popstate', restored);
          done();
        };
        window.addEventListener('popstate', restored);
        history.back();
      });
    });
    await expect(page).toHaveURL(/screen=profile&resource=100/);
    await expect(
      page.getByText('Результат операции пока неизвестен', { exact: true }),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'Повторить ту же операцию', exact: true })
      .click();
    await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
    expect(writes).toHaveLength(2);
    expect(writes[1]).toEqual(writes[0]);
    await expect(
      page.getByRole('textbox', { name: 'Описание клуба', exact: true }),
    ).toHaveValue(saved.description);
    await page
      .getByRole('button', {
        name: 'Настройки клуба',
        exact: true,
      })
      .click();
    await expect(page).toHaveURL(/screen=settings&resource=100/);
    await page
      .getByRole('button', { name: 'Назад к клубу', exact: true })
      .click();
    await page
      .getByRole('button', {
        name: 'Организация и другие заведения',
        exact: true,
      })
      .click();
    await expect(page).toHaveURL(/screen=client-space&resource=20/);
    await page.getByRole('button', { name: 'Клуб A1 Открыть клуб' }).click();
    await page
      .getByRole('button', {
        name: /Перейти к настройкам|Открыть настройки клуба/,
      })
      .click();
    await page.getByRole('button', { name: /^Профиль/ }).click();
    await expect(
      page.getByRole('textbox', { name: 'Описание клуба', exact: true }),
    ).toHaveValue(saved.description);
    await page.setViewportSize({ width: 320, height: 640 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });
}

test('an older profile snapshot revokes write and a late PATCH cannot restore it', async ({
  page,
}) => {
  let saved = profile;
  let finish: (() => Promise<void>) | undefined;
  await page.route('**/api/**', async (route) => {
    if (!new URL(route.request().url()).pathname.endsWith('/profile'))
      return route.fulfill({
        status: 503,
        json: { code: 'temporarily_unavailable' },
      });
    if (route.request().method() === 'PATCH') {
      await new Promise<void>((done) => {
        finish = async () => {
          await route.fulfill({
            json: { ...profile, name: 'Устаревшие права', version: '6' },
          });
          done();
        };
      });
      return;
    }
    return route.fulfill({ json: saved });
  });
  await page.goto(
    '/app?mode=client&screen=profile&resource=100#' +
      new URLSearchParams({
        tgWebAppData: credential,
        tgWebAppVersion: '7.10',
        tgWebAppPlatform: 'ios',
      }),
  );
  await page
    .getByRole('textbox', { name: 'Описание клуба', exact: true })
    .fill('Черновик');
  await page
    .getByRole('button', { name: 'Сохранить профиль', exact: true })
    .click();
  await expect.poll(() => Boolean(finish)).toBe(true);
  saved = {
    ...profile,
    name: 'Старый снимок',
    version: '2',
    permissions: ['client.club.read'],
    availableActions: [],
  };
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Профиль доступен только для просмотра.', { exact: true }),
  ).toBeVisible();
  await finish!();
  await expect(
    page.getByRole('heading', { name: profile.name, exact: true, level: 1 }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Сохранить профиль', exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText('Сохранено', { exact: true })).toHaveCount(0);
});

test('revoking read during an unknown command still permits returning to the cabinet', async ({
  page,
}) => {
  let allowed = true;
  let writes = 0;
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/context'))
      return route.fulfill({ json: { clients: [] } });
    if (!path.endsWith('/profile'))
      return route.fulfill({ status: 503, json: {} });
    if (request.method() === 'PATCH') {
      writes++;
      return route.abort('failed');
    }
    return route.fulfill({
      status: allowed ? 200 : 404,
      json: allowed ? profile : { code: 'not_found' },
    });
  });
  await page.goto(
    '/app?mode=client&screen=profile&resource=100#' +
      new URLSearchParams({
        tgWebAppData: credential,
        tgWebAppVersion: '7.10',
        tgWebAppPlatform: 'ios',
      }),
  );
  await page
    .getByRole('textbox', { name: 'Описание клуба', exact: true })
    .fill('Черновик');
  await page
    .getByRole('button', { name: 'Сохранить профиль', exact: true })
    .click();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toBeVisible();
  allowed = false;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByText(/Доступ утрачен/)).toBeVisible();
  const back = page.getByRole('button', {
    name: 'Мои заведения',
    exact: true,
  });
  await expect(back).toBeEnabled();
  page.once('dialog', (dialog) => dialog.accept());
  await back.click();
  await expect(
    page.getByRole('button', { name: 'Обновить кабинет', exact: true }),
  ).toBeVisible();
  expect(writes).toBe(1);
});
