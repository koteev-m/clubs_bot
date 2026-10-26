import { expect, test } from '@playwright/test';

test.use({
  baseURL: 'http://localhost:5173',
  viewport: { width: 390, height: 844 },
  hasTouch: true,
  isMobile: true,
});

test('local platform preview has truthful states, mobile layout and real back/forward navigation', async ({
  page,
}) => {
  const apiRequests: string[] = [];
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('http://localhost:5173/api/**', async (route) => {
    apiRequests.push(route.request().url());
    await route.abort();
  });
  await page.goto('/__preview/platform');
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
  await expect(page.getByText('Список клубов пока недоступен')).toBeVisible();
  await expect(
    page.getByText('Данные не подключены', { exact: true }),
  ).toHaveCount(2);
  for (const name of ['Клубы Позже', 'Доступы Позже', 'Настройки Позже']) {
    await expect(page.getByRole('button', { name })).toBeDisabled();
  }
  for (const width of [320, 390, 430]) {
    await page.setViewportSize({ width, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await expect(
      page.getByRole('button', { name: 'Пригласить владельца' }),
    ).toBeVisible();
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: test.info().outputPath('platform-overview.png'),
    fullPage: true,
  });
  const historyLength = await page.evaluate(() => history.length);
  await page.getByRole('button', { name: 'Пригласить владельца' }).click();
  await expect(page).toHaveURL(/\/__preview\/platform\/invite$/);
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toBeFocused();
  await expect(page.getByText('Владелец клуба', { exact: true })).toBeVisible();
  await expect(page.getByText('Новый клуб', { exact: true })).toBeVisible();
  await expect(page.getByRole('status')).toContainText(
    'Создание приглашений ещё не подключено',
  );
  await expect(page.getByRole('link')).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: /создать|отправить/i }),
  ).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: test.info().outputPath('platform-invite.png'),
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Назад к обзору' }).click();
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
  expect(await page.evaluate(() => history.length)).toBe(historyLength + 1);
  await page.goForward();
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toBeVisible();
  await page.goBack();
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
  // Simulate Telegram's inset variables and ensure content clears both inset layers.
  const safeArea = await page.evaluate(() => {
    const root = document.querySelector<HTMLElement>('.platform-owner')!;
    root.style.setProperty('--tg-safe-area-inset-top', '44px');
    root.style.setProperty('--tg-content-safe-area-inset-top', '56px');
    root.style.setProperty('--tg-safe-area-inset-bottom', '34px');
    return {
      top: parseFloat(
        getComputedStyle(document.querySelector('.po-header')!).paddingTop,
      ),
      bottom: parseFloat(
        getComputedStyle(document.querySelector('.po-nav')!).paddingBottom,
      ),
    };
  });
  expect(safeArea.top).toBeGreaterThanOrEqual(120);
  expect(safeArea.bottom).toBeGreaterThanOrEqual(46);
  expect(apiRequests).toEqual([]);
  expect(errors).toEqual([]);
});

test('direct invitation entry, reload and Telegram Back return to overview', async ({
  page,
}) => {
  await page.goto(
    '/__preview/platform/invite#tgWebAppVersion=7.0&tgWebAppPlatform=ios',
  );
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Назад к обзору' }).click();
  await expect(page).toHaveURL(/\/__preview\/platform$/);
  await page.goForward();
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toBeVisible();
  // The real SDK receives Telegram's native back_button_pressed event.
  await page.evaluate(() => {
    const telegram = window as unknown as {
      Telegram: { WebView: { receiveEvent: (event: string) => void } };
    };
    telegram.Telegram.WebView.receiveEvent('back_button_pressed');
  });
  await expect(page).toHaveURL(/\/__preview\/platform$/);
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
});

test('ordinary guest entry stays separate from platform preview', async ({ page }) => {
  await page.route('http://localhost:5173/api/clubs', (route) => route.fulfill({ json: [] }));
  await page.route('http://localhost:5173/api/support/tickets/my', (route) => route.fulfill({ json: [] }));
  await page.goto('/?mode=guest&role=OWNER&scope=GLOBAL');
  await expect(page.getByText('Шаг 1. Выбор клуба', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Забронировать', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Пригласить владельца' })).toHaveCount(0);
});

test('owner claims in query do not enable platform controls', async ({
  page,
}) => {
  await page.goto('/?mode=owner&role=OWNER&scope=GLOBAL');
  await expect(
    page.getByRole('heading', {
      name: 'Доступ к управлению платформой не подключён',
    }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Пригласить владельца' }),
  ).toHaveCount(0);
});
