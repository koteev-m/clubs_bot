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
    await page.route('**/api/**', async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path.endsWith('/layout')) return route.fulfill({ json: layout });
      if (path.endsWith('/halls/1/plan')) {
        if (request.method() === 'POST') {
          writes.push(path);
          firstImage = images.replacement;
          layout = {
            ...layout,
            halls: layout.halls.map((hall) =>
              hall.id === '1'
                ? {
                    ...hall,
                    layoutRevision: '2',
                    plan: { ...hall.plan!, sha256: 'A2' },
                  }
                : hall,
            ),
          };
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
    await page.locator('input[type="file"]').setInputFiles({
      name: 'replacement.png',
      mimeType: 'image/png',
      buffer: Buffer.from(images.replacement, 'base64'),
    });
    await expect.poll(imageColor).toEqual([0, 128, 0]);
    expect(writes).toEqual(['/api/client/clubs/42/halls/1/plan']);
    const switchHall = async (id: string) => {
      await page.getByRole('button', { name: 'Назад', exact: true }).click();
      const name = layout.halls.find((hall) => hall.id === id)!.name;
      await page
        .locator('.client-layout section')
        .filter({
          has: page.getByRole('heading', { name: 'Залы', exact: true }),
        })
        .locator('.rounded.border.p-3')
        .filter({ hasText: name })
        .getByRole('button', { name: 'Открыть схему', exact: true })
        .click();
    };
    await switchHall('2');
    await expect(
      page.getByText('Зал «Welcome». Здесь показаны только его схема и столы.'),
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
  });
}
