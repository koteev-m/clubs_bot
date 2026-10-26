import { expect, test } from '@playwright/test';

test.use({
  baseURL: 'http://localhost:5173',
  viewport: { width: 390, height: 844 },
  hasTouch: true,
  isMobile: true,
});

const telegramStub = `window.Telegram={WebApp:{initData:'synthetic-init-data',platform:'ios',version:'7.0',ready:()=>{},expand:()=>{},colorScheme:'light',onEvent:()=>{},offEvent:()=>{},MainButton:{setText:()=>{},show:()=>{},hide:()=>{},onClick:()=>{},offClick:()=>{}}}}`;

test('server-confirmed owner can navigate both real screens without invitation effects', async ({
  page,
}) => {
  await page.addInitScript(telegramStub);
  const requests: string[] = [];
  await page.route('http://localhost:5173/api/**', async (route) => {
    const request = route.request();
    requests.push(`${request.method()} ${new URL(request.url()).pathname}`);
    expect(request.headers()['x-telegram-init-data']).toBe(
      'synthetic-init-data',
    );
    expect(new URL(request.url()).search).toBe('');
    expect(request.postData()).toBeNull();
    await route.fulfill({ json: { platformOwner: true } });
  });
  await page.goto(
    '/app?mode=platform-owner#tgWebAppData=synthetic-init-data&tgWebAppVersion=7.0&tgWebAppPlatform=ios',
  );
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
  await expect(
    page.getByText('Владелец платформы', { exact: true }),
  ).toBeVisible();
  await expect(page.getByText('Локальный просмотр')).toHaveCount(0);
  await page.getByRole('button', { name: 'Пригласить владельца' }).click();
  await expect(page).toHaveURL(/screen=invite/);
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toBeVisible();
  await expect(page.getByRole('status')).toContainText(
    'Создание приглашений ещё не подключено',
  );
  await page.getByRole('button', { name: 'Назад к обзору' }).click();
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
  await page.goForward();
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Назад к обзору' }).click();
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
  expect(requests.length).toBeGreaterThan(0);
  expect(requests.every((value) => value === 'GET /api/platform/access')).toBe(
    true,
  );
});

test('direct invitation entry rechecks access and returns to overview', async ({
  page,
}) => {
  await page.addInitScript(telegramStub);
  await page.route('http://localhost:5173/api/platform/access', (route) =>
    route.fulfill({ json: { platformOwner: true } }),
  );
  await page.goto(
    '/app?mode=platform-owner&screen=invite#tgWebAppData=synthetic-init-data&tgWebAppVersion=7.0&tgWebAppPlatform=ios',
  );
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Назад к обзору' }).click();
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
});

test('server refusal cannot be bypassed by query or persisted frontend state', async ({
  page,
}) => {
  await page.addInitScript(
    `${telegramStub};localStorage.setItem('platformOwner','true');`,
  );
  await page.route('http://localhost:5173/api/platform/access', (route) =>
    route.fulfill({ status: 403, json: { code: 'forbidden' } }),
  );
  await page.goto(
    '/app?mode=platform-owner&screen=invite&role=OWNER&userId=42&scope=GLOBAL#tgWebAppData=synthetic-init-data&tgWebAppVersion=7.0&tgWebAppPlatform=ios',
  );
  await expect(
    page.getByRole('heading', { name: 'Нет доступа к управлению платформой' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Пригласить владельца' }),
  ).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: 'Пригласить владельца' }),
  ).toHaveCount(0);
  await page.reload();
  await expect(
    page.getByRole('heading', { name: 'Нет доступа к управлению платформой' }),
  ).toBeVisible();
});

test('network failure stays closed and a retry uses a new server response', async ({
  page,
}) => {
  await page.addInitScript(telegramStub);
  let checks = 0;
  await page.route(
    'http://localhost:5173/api/platform/access',
    async (route) => {
      checks++;
      if (checks === 1) await route.abort();
      else await route.fulfill({ json: { platformOwner: true } });
    },
  );
  await page.goto(
    '/app?mode=platform-owner#tgWebAppData=synthetic-init-data&tgWebAppVersion=7.0&tgWebAppPlatform=ios',
  );
  await expect(
    page.getByRole('heading', { name: 'Не удалось проверить доступ' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Пригласить владельца' }),
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Повторить проверку' }).click();
  await expect(
    page.getByRole('heading', { name: 'Управление платформой' }),
  ).toBeVisible();
  expect(checks).toBe(2);
});
