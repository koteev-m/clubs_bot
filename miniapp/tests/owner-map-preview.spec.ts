import { expect, test } from '@playwright/test';

const clubId = '9007199254740999';
const hallId = '9007199254741000';
const tableId = '9007199254741001';
const signed = new URLSearchParams({
  user: JSON.stringify({ id: 880062010, first_name: 'Synthetic' }),
  auth_date: '1',
  hash: 'synthetic',
}).toString();
const telegramHash =
  '#tgWebAppData=' +
  encodeURIComponent(signed) +
  '&tgWebAppVersion=7.10&tgWebAppPlatform=ios';
const entry =
  '/app?mode=client&screen=layout&resource=' +
  clubId +
  '&hall=' +
  hallId +
  telegramHash;
// A valid square PNG makes horizontal object-contain letterboxing observable.
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+nmWQAAAAASUVORK5CYII=',
  'base64',
);
const layout = {
  id: clubId,
  clubId,
  clientId: '20',
  permissions: ['client.club.read', 'client.club.layout.write'],
  halls: [
    {
      id: hallId,
      name: 'Сохранённый зал',
      layoutRevision: '4',
      plan: {
        contentType: 'image/png',
        sizeBytes: png.length,
        sha256: 'synthetic',
      },
      tables: [
        {
          id: tableId,
          hallId,
          tableNumber: 7,
          label: 'У окна',
          capacity: 6,
          x: 0,
          y: 0.25,
        },
      ],
    },
  ],
};

test('saved guest preview selects real tables, keeps contain coordinates across zoom/reopen and clears private data on revoke', async ({
  page,
}) => {
  let revoked = false;
  const mutations: string[] = [];
  const reads: string[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    reads.push(path);
    if (request.method() !== 'GET') mutations.push(path);
    if (revoked)
      return route.fulfill({ status: 404, json: { code: 'not_found' } });
    if (path.endsWith('/layout')) return route.fulfill({ json: layout });
    if (path.endsWith('/plan'))
      return route.fulfill({ contentType: 'image/png', body: png });
    return route.fulfill({ status: 404, json: { code: 'not_found' } });
  });
  await page.goto(entry);
  await page
    .getByRole('button', { name: 'Предпросмотр для гостя', exact: true })
    .click();
  const image = page.getByTestId('hall-plan-image');
  await expect(image).toBeVisible();
  await expect
    .poll(() =>
      image.evaluate((node) => (node as HTMLImageElement).naturalWidth),
    )
    .toBeGreaterThan(0);
  const marker = page.getByTestId('table-marker-' + tableId);
  await expect(marker).toBeVisible();
  const markerBox = await marker.boundingBox();
  expect(markerBox?.width).toBe(44);
  expect(markerBox?.height).toBe(44);

  const coordinates = () =>
    marker.evaluate((node) => {
      const stage = node.closest('[data-testid=hall-plan-stage]')!;
      const picture = stage.querySelector('img')!;
      const rect = stage.getBoundingClientRect();
      const button = node.getBoundingClientRect();
      const scale = Math.min(
        rect.width / picture.naturalWidth,
        rect.height / picture.naturalHeight,
      );
      const width = scale * picture.naturalWidth;
      const height = scale * picture.naturalHeight;
      return {
        x:
          (button.x + button.width / 2 - rect.x - (rect.width - width) / 2) /
          width,
        y:
          (button.y + button.height / 2 - rect.y - (rect.height - height) / 2) /
          height,
      };
    });
  expect((await coordinates()).x).toBeCloseTo(0, 2);
  expect((await coordinates()).y).toBeCloseTo(0.25, 2);
  await marker.tap();
  const card = page.getByRole('region', { name: 'Карточка стола' });
  await expect(card).toBeVisible();
  await expect(card.getByText('Стол №7')).toBeVisible();
  await expect(card.getByText('У окна')).toBeVisible();
  await expect(card.getByText('6 мест')).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Сохранить', exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: /Бронировать|Создать бронь/ }),
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Увеличить', exact: true }).click();
  await expect(page.getByText('150%', { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 430, height: 620 });
  await expect.poll(async () => (await coordinates()).x).toBeCloseTo(0, 2);
  await expect.poll(async () => (await coordinates()).y).toBeCloseTo(0.25, 2);
  await page.getByRole('button', { name: 'Вписать схему' }).click();
  await page.screenshot({
    path: test.info().outputPath('saved-table-mobile-preview.png'),
    fullPage: true,
  });
  await page.goto(entry);
  // Reopening an identical path with only launch hash changes is otherwise same-document navigation.
  await page.reload();
  await page
    .getByRole('button', { name: 'Предпросмотр для гостя', exact: true })
    .click();
  await page.getByRole('button', { name: '#7 · У окна' }).click();
  await expect(card.getByText('6 мест')).toBeVisible();
  revoked = true;
  await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await expect(
    page.getByText(/Доступ утрачен или запись недоступна/),
  ).toBeVisible();
  await expect(image).toHaveCount(0);
  await expect(card).toHaveCount(0);
  expect(mutations).toEqual([]);
  expect(
    reads.some((path) => /booking|hold|geojson|api\/admin/.test(path)),
  ).toBe(false);
});
