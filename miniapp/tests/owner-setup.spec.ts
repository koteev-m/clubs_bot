import { expect, test, type Locator } from '@playwright/test';
import type { ClientLayout } from '../src/modules/client/layout.api';

const credential = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const entry =
  '/app?mode=client&screen=layout&resource=42&hall=1#tgWebAppData=' +
  encodeURIComponent(credential) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';

async function contrast(locator: Locator, pseudo?: string) {
  return locator.evaluate((element, pseudo) => {
    const style = getComputedStyle(element, pseudo);
    const rgb = (value: string) =>
      value
        .match(/[\d.]+/g)!
        .slice(0, 3)
        .map(Number);
    const luminance = (color: number[]) =>
      color
        .map((part) => {
          const channel = part / 255;
          return channel <= 0.04045
            ? channel / 12.92
            : ((channel + 0.055) / 1.055) ** 2.4;
        })
        .reduce(
          (sum, part, index) => sum + part * [0.2126, 0.7152, 0.0722][index],
          0,
        );
    let parent: Element | null = element;
    let background = getComputedStyle(element).backgroundColor;
    while (background === 'rgba(0, 0, 0, 0)' && parent?.parentElement) {
      parent = parent.parentElement;
      background = getComputedStyle(parent).backgroundColor;
    }
    const values = [
      luminance(rgb(style.color)),
      luminance(rgb(background)),
    ].sort((a, b) => a - b);
    return (values[1] + 0.05) / (values[0] + 0.05);
  }, pseudo);
}

for (const colorScheme of ['light', 'dark'] as const) {
  test(`independent halls and accessible table creation in Telegram ${colorScheme} theme`, async ({
    page,
  }, info) => {
    await page.emulateMedia({ colorScheme });
    await page.setViewportSize({ width: 390, height: 844 });
    // Telegram supplies launch data to each new WebView document; the app removes it from the URL.
    await page.addInitScript((credential) => {
      if (location.pathname !== '/app') return;
      const url = new URL(location.href);
      url.hash = new URLSearchParams({
        tgWebAppData: credential,
        tgWebAppVersion: '7.10',
        tgWebAppPlatform: 'ios',
      }).toString();
      history.replaceState(history.state, '', url);
    }, credential);
    const images = await page.evaluate(() => {
      const image = (color: string, format: string) => {
        const canvas = document.createElement('canvas');
        canvas.width = 80;
        canvas.height = 40;
        const context = canvas.getContext('2d')!;
        context.fillStyle = color;
        context.fillRect(0, 0, 80, 40);
        return canvas.toDataURL(format).split(',')[1];
      };
      return {
        png: image('red', 'image/png'),
        jpeg: image('blue', 'image/jpeg'),
        replacement: image('green', 'image/png'),
      };
    });
    let firstImage = images.png;
    let layout: ClientLayout = {
      id: '42',
      clubId: '42',
      clientId: '21',
      permissions: ['client.club.read', 'client.club.layout.write'],
      halls: [
        {
          id: '1',
          name: 'Main',
          layoutRevision: '1',
          plan: { contentType: 'image/png', sha256: 'A', sizeBytes: 80 },
          tables: [
            {
              id: '11',
              hallId: '1',
              tableNumber: 7,
              label: 'Main table',
              capacity: 4,
              x: 0.2,
              y: 0.7,
              bookingLinked: true,
            },
          ],
        },
        {
          id: '2',
          name: 'Welcome',
          layoutRevision: '1',
          plan: { contentType: 'image/jpeg', sha256: 'B', sizeBytes: 80 },
          tables: [
            {
              id: '22',
              hallId: '2',
              tableNumber: 8,
              label: 'Welcome table',
              capacity: 2,
              x: null,
              y: null,
              bookingLinked: true,
            },
          ],
        },
        { id: '3', name: 'Empty', layoutRevision: '1', plan: null, tables: [] },
      ],
    };
    const writes: string[] = [];
    const removes: Array<{ key: string; body: unknown }> = [];
    const updateMain = (changes: Partial<ClientLayout['halls'][number]>) => {
      layout = {
        ...layout,
        halls: layout.halls.map((hall) =>
          hall.id === '1' ? { ...hall, ...changes } : hall,
        ),
      };
    };
    await page.route('**/api/**', async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path.endsWith('/layout')) return route.fulfill({ json: layout });
      if (path.endsWith('/halls/1/plan/remove')) {
        removes.push({
          key: request.headers()['idempotency-key'],
          body: request.postDataJSON(),
        });
        if (removes.length === 1) {
          updateMain({ layoutRevision: '3' });
          return route.fulfill({
            status: 409,
            json: { code: 'version_conflict' },
          });
        }
        if (removes.length === 2) {
          updateMain({ layoutRevision: '4', plan: null });
          return route.abort('failed');
        }
        return route.fulfill({ json: layout });
      }
      if (path.endsWith('/halls/1/plan')) {
        if (request.method() === 'POST') {
          writes.push(path);
          firstImage = images.replacement;
          updateMain({
            layoutRevision: String(Number(layout.halls[0].layoutRevision) + 1),
            plan: { contentType: 'image/png', sha256: 'A2', sizeBytes: 80 },
          });
          return route.fulfill({ json: layout });
        }
        return route.fulfill({
          body: Buffer.from(firstImage, 'base64'),
          contentType: 'image/png',
        });
      }
      if (path.endsWith('/halls/2/plan')) {
        await new Promise((resolve) => setTimeout(resolve, 200));
        return route.fulfill({
          body: Buffer.from(images.jpeg, 'base64'),
          contentType: 'image/jpeg',
        });
      }
      return route.fulfill({ status: 404, json: { code: 'not_found' } });
    });
    await page.goto(entry);
    const plan = page.getByAltText('План зала');
    const imageColor = () =>
      plan.evaluate((element) => {
        const canvas = document.createElement('canvas');
        canvas.width = 1;
        canvas.height = 1;
        const context = canvas.getContext('2d')!;
        context.drawImage(element as HTMLImageElement, 0, 0, 1, 1);
        return Array.from(context.getImageData(0, 0, 1, 1).data).slice(0, 3);
      });
    await expect.poll(imageColor).toEqual([255, 0, 0]);
    await expect(page.getByText('На схеме: 1 из 1')).toBeVisible();
    const replacement = {
      name: 'replacement.png',
      mimeType: 'image/png',
      buffer: Buffer.from(images.replacement, 'base64'),
    };
    const replace = page.getByLabel('Заменить схему', { exact: true });
    await page.getByRole('button', { name: 'Предпросмотр для гостя' }).click();
    await expect(replace).toBeDisabled();
    await expect(
      page.getByText('Вернитесь к редактированию, чтобы изменить изображение.'),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'Вернуться к редактированию' })
      .click();
    await expect(replace).toBeEnabled();
    const choose = page.waitForEvent('filechooser');
    await replace.click();
    await (await choose).setFiles(replacement);
    await expect.poll(imageColor).toEqual([0, 128, 0]);
    expect(writes).toEqual(['/api/client/clubs/42/halls/1/plan']);
    const switchHall = async (id: string) => {
      await page.getByRole('button', { name: 'Назад', exact: true }).click();
      const name = layout.halls.find((hall) => hall.id === id)!.name;
      const card = page.locator('.client-hall-card').filter({
        has: page.getByRole('heading', { name, exact: true, level: 3 }),
      });
      const title = card.getByRole('heading');
      expect(await contrast(title)).toBeGreaterThanOrEqual(4.5);
      expect(
        await title.evaluate((element) =>
          parseFloat(getComputedStyle(element).fontSize),
        ),
      ).toBeGreaterThanOrEqual(22);
      const open = card.getByRole('button', {
        name: 'Открыть зал',
        exact: true,
      });
      expect((await open.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      await open.click();
      await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
    };
    await switchHall('2');
    await expect(
      page.getByRole('heading', { name: 'Welcome', level: 1 }),
    ).toBeVisible();
    await expect.poll(imageColor).toEqual([0, 0, 254]);
    await expect(
      page.getByRole('button', { name: '#7 · Main table', exact: true }),
    ).toHaveCount(0);
    await expect(page.getByText('На схеме: 0 из 1')).toBeVisible();
    await page.getByRole('button', { name: 'Добавить столы' }).click();
    const form = page.getByRole('form', { name: 'Создание столов' });
    const number = form.getByLabel('Номер нового стола');
    const select = form.getByLabel('Способ добавления');
    expect(await contrast(form)).toBeGreaterThanOrEqual(4.5);
    expect(await contrast(number)).toBeGreaterThanOrEqual(4.5);
    expect(await contrast(select)).toBeGreaterThanOrEqual(4.5);
    expect(
      await contrast(select.locator('option').first()),
    ).toBeGreaterThanOrEqual(4.5);
    expect(
      await contrast(
        form.getByRole('button', { name: 'Создать стол', exact: true }),
      ),
    ).toBeGreaterThanOrEqual(4.5);
    await number.fill('7');
    await expect(form.getByRole('alert')).toContainText(
      '№7 уже занят в зале «Main»',
    );
    expect(await contrast(form.getByRole('alert'))).toBeGreaterThanOrEqual(4.5);
    await number.focus();
    expect(
      await number.evaluate(
        (element) => getComputedStyle(element).outlineStyle,
      ),
    ).not.toBe('none');
    await select.selectOption('list');
    expect(
      await contrast(form.getByLabel('Номера столов'), '::placeholder'),
    ).toBeGreaterThanOrEqual(4.5);
    await form.getByLabel('Номера столов').fill('12, 18');
    expect(
      await contrast(
        form.getByRole('button', { name: 'Создать столы', exact: true }),
      ),
    ).toBeGreaterThanOrEqual(4.5);
    await page.screenshot({
      path: info.outputPath(`table-form-${colorScheme}.png`),
      fullPage: true,
    });
    await form.getByRole('button', { name: 'Отмена' }).click();
    await switchHall('3');
    await expect(page.getByText('План еще не загружен.')).toBeVisible();
    await expect(plan).toHaveCount(0);
    await expect(page.getByText('PNG/JPEG, до 5 МБ.')).toBeVisible();
    await switchHall('1');
    await expect.poll(imageColor).toEqual([0, 128, 0]);
    await page.reload();
    await expect.poll(imageColor).toEqual([0, 128, 0]);
    expect(writes).toHaveLength(1);

    const remove = page.getByRole('button', {
      name: 'Удалить изображение схемы',
    });
    page.once('dialog', (dialog) => dialog.dismiss());
    await remove.click();
    expect(removes).toHaveLength(0);
    page.once('dialog', (dialog) => dialog.accept());
    await remove.click();
    await expect(page.getByText(/Данные зала изменились/)).toBeVisible();
    await expect(remove).toBeDisabled();
    await page
      .getByRole('button', { name: 'Загрузить актуальные данные' })
      .click();
    await expect(remove).toBeEnabled();
    page.once('dialog', (dialog) => dialog.accept());
    await remove.click();
    await expect(
      page.getByText(/Результат операции пока неизвестен/),
    ).toBeVisible();
    await expect(replace).toBeDisabled();
    await page
      .getByRole('button', { name: 'Повторить ту же операцию' })
      .click();
    await expect(
      page.getByLabel('Загрузить схему', { exact: true }),
    ).toBeEnabled();
    await expect(plan).toHaveCount(0);
    expect(removes[1]).toEqual(removes[2]);
    expect(removes[1].body).toEqual({ expectedVersion: '3' });
    await expect(
      page.getByText('Столов с сохранёнными координатами: 1 из 1'),
    ).toBeVisible();
    await page.reload();
    await expect(page.getByText('План еще не загружен.')).toBeVisible();
    await switchHall('2');
    await expect.poll(imageColor).toEqual([0, 0, 254]);
    await switchHall('1');
    await page
      .getByLabel('Загрузить схему', { exact: true })
      .setInputFiles(replacement);
    await expect.poll(imageColor).toEqual([0, 128, 0]);
    await expect(page.getByTestId('table-marker-11')).toBeVisible();
    expect(writes).toHaveLength(2);
    await page.setViewportSize({ width: 320, height: 620 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });
}

for (const mode of ['client', 'cabinet']) {
  test(`opening halls keeps the first tap during ${mode} foreground revalidation`, async ({
    page,
  }) => {
    let finishCheck!: () => void;
    let checking = false;
    const revalidation = new Promise<void>((resolve) => {
      finishCheck = resolve;
    });
    const profile = {
      id: '42',
      clientId: '21',
      clientName: 'Test',
      name: 'Test club',
      description: '',
      timezone: 'Europe/Moscow',
      countryCode: 'RU',
      city: 'Москва',
      address: 'Test',
      unit: '',
      version: '1',
      permissions: ['client.club.read', 'client.club.layout.write'],
      availableActions: [],
    };
    await page.route('**/api/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (checking && !path.endsWith('/layout')) await revalidation;
      if (path === '/api/platform/access')
        return route.fulfill({ status: 403, json: { code: 'forbidden' } });
      if (path === '/api/client/context')
        return route.fulfill({ json: { clients: [{ id: '21' }] } });
      if (path.endsWith('/profile')) return route.fulfill({ json: profile });
      return route.fulfill({
        json: {
          clubId: '42',
          clientId: '21',
          permissions: profile.permissions,
          halls: [],
        },
      });
    });
    await page.goto(
      entry
        .replace('screen=layout&resource=42&hall=1', 'screen=club&resource=42')
        .replace('mode=client', 'mode=' + mode),
    );
    const open = page.getByRole('button', { name: /Залы и столы/ });
    await open.scrollIntoViewIfNeeded();
    const target = (await open.boundingBox())!;
    await page.mouse.move(
      target.x + target.width / 2,
      target.y + target.height / 2,
    );
    await page.mouse.down();
    checking = true;
    const requested = page.waitForRequest((request) =>
      request.url().endsWith('/profile'),
    );
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    try {
      await requested;
      await page.mouse.up();
      await expect(
        page.getByRole('heading', { name: 'Залы и столы' }),
      ).toBeVisible({ timeout: 2500 });
    } finally {
      finishCheck();
    }
  });
}

test('owner Back uses browser parents, protects a draft, and exposes native close at the root', async ({
  page,
}) => {
  const organization = {
    id: '21',
    name: 'Test organization',
    clubs: [{ id: '42', name: 'Test club' }],
  };
  const permissions = ['client.club.read', 'client.club.layout.write'];
  await page.addInitScript(() => {
    Object.assign(window, {
      TelegramWebviewProxy: {
        postEvent: (event: string) => {
          if (event === 'web_app_close')
            document.documentElement.dataset.nativeClose = 'sent';
        },
      },
    });
  });
  await page.route('**/api/client/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/context'))
      return route.fulfill({ json: { clients: [organization] } });
    if (path.endsWith('/onboarding'))
      return route.fulfill({ json: { ...organization, permissions } });
    if (path.endsWith('/profile'))
      return route.fulfill({
        json: {
          id: '42',
          clientId: '21',
          clientName: organization.name,
          name: 'Test club',
          description: '',
          timezone: 'Europe/Moscow',
          countryCode: 'RU',
          city: 'Москва',
          address: 'Test',
          unit: '',
          version: '1',
          permissions,
          availableActions: [],
        },
      });
    return route.fulfill({
      json: {
        id: '42',
        clubId: '42',
        clientId: '21',
        permissions,
        halls: [
          {
            id: '1',
            name: 'Main',
            plan: null,
            layoutRevision: '1',
            tables: [
              {
                id: '11',
                tableNumber: 7,
                label: 'Main table',
                capacity: 4,
                x: null,
                y: null,
              },
            ],
          },
        ],
      },
    });
  });
  const nativeBack = () =>
    page.evaluate(() => {
      const telegram = window.Telegram as unknown as {
        WebView: { receiveEvent: (name: string, data: object) => void };
      };
      telegram.WebView.receiveEvent('back_button_pressed', {});
    });
  const profileBack = page.getByRole('button', {
    name: 'Организация и другие заведения',
  });
  const openClub = page.getByRole('button', {
    name: /Test club.*Открыть клуб/,
  });
  const openOrganization = page.getByRole('button', {
    name: /Test organization.*Открыть заведения/,
  });
  const close = page.getByRole('button', { name: 'Закрыть приложение' });
  const nativeVisible = () =>
    page.evaluate(() => window.Telegram.WebApp.BackButton.isVisible);
  await page.goto(entry.replace('&screen=layout&resource=42&hall=1', ''));
  await profileBack.click();
  await expect(openClub).toBeVisible();
  await nativeBack();
  await expect(openOrganization).toBeVisible();
  await expect.poll(nativeVisible).toBe(false);
  await expect(close).toBeVisible();

  await openOrganization.click();
  await openClub.click();
  await page.getByRole('button', { name: /Залы и столы/ }).click();
  const openHall = page.getByRole('button', { name: 'Открыть зал' });
  await openHall.click();
  const main = page.getByRole('heading', { name: 'Main', level: 1 });
  await expect(main).toBeVisible();
  await expect.poll(nativeVisible).toBe(true);
  await expect(close).toHaveCount(0);
  const length = await page.evaluate(() => history.length);

  await page.getByRole('button', { name: '#7 · Main table' }).click();
  const label = page.getByLabel('Название', { exact: true });
  await label.fill('Draft');
  const dismissed = page.waitForEvent('dialog');
  page.once('dialog', (dialog) => dialog.dismiss());
  await nativeBack();
  await dismissed;
  await expect(label).toHaveValue('Draft');
  await page.evaluate(() => {
    const confirm = window.confirm;
    window.confirm = (message) => {
      window.confirm = confirm;
      document.documentElement.dataset.browserBackPrompt = message;
      return false;
    };
    history.back();
  });
  await expect(page.locator('html')).toHaveAttribute(
    'data-browser-back-prompt',
    /несохранённые/,
  );
  await expect(page).toHaveURL(/hall=1/);
  await expect(label).toHaveValue('Draft');

  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(openHall).toBeVisible();
  await page.goForward();
  await expect(main).toBeVisible();
  await nativeBack();
  await expect(openHall).toBeVisible();
  await nativeBack();
  await expect(profileBack).toBeVisible();
  await profileBack.click();
  await expect(openClub).toBeVisible();
  await nativeBack();
  await expect(openOrganization).toBeVisible();
  await expect.poll(nativeVisible).toBe(false);
  expect(await page.evaluate(() => history.length)).toBe(length);
  await close.click();
  await expect(page.locator('html')).toHaveAttribute(
    'data-native-close',
    'sent',
  );
});
