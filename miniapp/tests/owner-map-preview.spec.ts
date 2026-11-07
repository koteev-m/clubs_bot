import { createHash } from 'node:crypto';
import { expect, test } from '@playwright/test';
import type { ClientLayout } from '../src/modules/client/layout.api';

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
const layout: ClientLayout = {
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
        sha256: createHash('sha256').update(png).digest('hex'),
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

test('mobile editor places saved physical tables and keeps the original command after a lost response', async ({
  page,
}) => {
  test.setTimeout(60_000);
  const current = structuredClone(layout);
  const hall = current.halls[0];
  hall.tables = [];
  const savedPlan = hall.plan;
  hall.plan = null;
  const writes: { method: string; key: string; body: string }[] = [];
  let uploads = 0;
  let loseNextSave = false;
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (path.endsWith('/layout')) return route.fulfill({ json: current });
    if (path.endsWith('/plan')) {
      if (method === 'POST') {
        uploads += 1;
        hall.plan = savedPlan;
        return route.fulfill({ json: current });
      }
      return route.fulfill({ contentType: 'image/png', body: png });
    }
    const body = request.postData();
    if (!body || !/\/tables(?:\/(?:batch|\d+))?$/.test(path))
      return route.fulfill({ status: 404, json: { code: 'not_found' } });
    writes.push({ method, key: request.headers()['idempotency-key'], body });
    const fields = request.postDataJSON();
    if (method === 'POST') {
      for (const number of fields.tableNumbers) {
        hall.tables.push({
          id: String(BigInt(tableId) + BigInt(hall.tables.length)),
          hallId,
          tableNumber: number,
          label: `Стол ${number}`,
          capacity: fields.capacity,
          bookingLinked: true,
          x: null,
          y: null,
        });
      }
    } else if (method === 'PATCH') {
      const saved = hall.tables.find((table) => path.endsWith('/' + table.id))!;
      delete fields.expectedVersion;
      Object.assign(saved, fields);
      if (loseNextSave) {
        loseNextSave = false;
        return route.abort();
      }
    }
    return route.fulfill({ json: current });
  });

  await page.goto(entry);
  await expect(
    page.getByRole('heading', { name: 'Список столов' }),
  ).toBeVisible();
  await expect(page.getByText('План еще не загружен.')).toBeVisible();
  await page.getByRole('button', { name: 'Добавить столы' }).tap();
  await page.getByLabel('Способ добавления').selectOption('list');
  await page.getByLabel('Номера столов').fill('101, 107, 101');
  await expect(
    page.getByRole('button', { name: 'Создать столы' }),
  ).toBeDisabled();
  await expect(page.getByText(/№101 повторяется в списке/)).toBeVisible();
  await page.getByLabel('Способ добавления').selectOption('range');
  await page
    .getByRole('textbox', { name: 'Первый номер', exact: true })
    .fill('101');
  await page.getByLabel('Количество столов').fill('15');
  await expect(
    page.getByText(/Номера для создания: 101, 102, 103/),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Создать столы' }).tap();
  await expect(
    page.getByText('Столов с сохранёнными координатами: 0 из 15'),
  ).toBeVisible();
  expect(
    hall.tables.every((table) => table.x === null && table.y === null),
  ).toBe(true);
  expect(writes.map(({ method }) => method)).toEqual(['POST']);
  await page
    .locator('input[type=file]')
    .setInputFiles({ name: 'hall.png', mimeType: 'image/png', buffer: png });
  const stage = page.getByTestId('hall-plan-stage');
  await expect
    .poll(() =>
      page
        .getByTestId('hall-plan-image')
        .evaluate((img) => (img as HTMLImageElement).naturalWidth),
    )
    .toBeGreaterThan(0);
  const box = (await stage.boundingBox())!;
  for (let index = 0; index < 15; index += 1) {
    await stage.tap({
      position: {
        x: box.width / 2 + (((index % 5) + 0.5) / 5 - 0.5) * box.height,
        y: ((Math.floor(index / 5) + 0.5) / 3) * box.height,
      },
    });
    const picker = page.getByRole('region', {
      name: 'Выбор неразмещённого стола',
    });
    await expect(
      picker.getByRole('button', { name: /^Разместить №/ }),
    ).toHaveCount(15 - index);
    await picker
      .getByRole('button', {
        name: new RegExp(`^Разместить №${101 + index} ·`),
      })
      .tap();
    await expect(page.getByText(`На схеме: ${index + 1} из 15`)).toBeVisible();
  }
  const marker = page.getByTestId('table-marker-' + tableId);
  expect(writes.filter(({ method }) => method === 'POST')).toHaveLength(1);
  const originalX = hall.tables[0].x!;
  await marker.scrollIntoViewIfNeeded();
  const markerBox = (await marker.boundingBox())!;
  await page.mouse.move(
    markerBox.x + markerBox.width / 2,
    markerBox.y + markerBox.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    markerBox.x + markerBox.width / 2 + 12,
    markerBox.y + markerBox.height / 2 + 10,
    { steps: 4 },
  );
  await page.mouse.up();
  await expect.poll(() => hall.tables[0].x!).toBeGreaterThan(originalX);
  await expect(page.getByLabel('Название', { exact: true })).toBeEditable();

  await page.getByLabel('Название', { exact: true }).fill('В центре');
  loseNextSave = true;
  await page.getByRole('button', { name: 'Сохранить и завершить' }).tap();
  const retry = page.getByRole('button', { name: 'Повторить ту же операцию' });
  await expect(retry).toBeVisible();
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue(
    'В центре',
  );
  const pending = writes.at(-1)!;
  await retry.tap();
  await expect(
    page.getByRole('button', { name: '#101 · В центре' }),
  ).toBeVisible();
  expect(pending.key).toBeTruthy();
  expect(writes.at(-1)).toEqual(pending);

  await marker.tap();
  await page.getByRole('button', { name: 'Убрать со схемы' }).tap();
  await expect(page.getByText('На схеме: 14 из 15')).toBeVisible();
  await expect(marker).toHaveCount(0);
  await page.locator('input[type=file]').setInputFiles({
    name: 'replacement.png',
    mimeType: 'image/png',
    buffer: png,
  });
  await expect.poll(() => uploads).toBe(2);
  await page.goto(entry);
  await page.reload();
  await expect(page.getByText('На схеме: 14 из 15')).toBeVisible();
  await expect(
    page.getByRole('button', { name: '#101 · В центре' }),
  ).toBeVisible();
  expect(hall.tables).toHaveLength(15);
});
