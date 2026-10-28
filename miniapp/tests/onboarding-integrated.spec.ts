import { expect, test, APIRequestContext, Page } from '@playwright/test';

async function identity(
  request: APIRequestContext,
  role: string,
  startParam?: string,
): Promise<string> {
  const response = startParam
    ? await request.post('/fixture/identity/' + role, { data: { startParam } })
    : await request.get('/fixture/identity/' + role);
  expect(response.ok()).toBe(true);
  return (await response.json()).initData;
}
async function open(page: Page, path: string, initData: string) {
  const current = new URL(page.url());
  const sameDocument = current.pathname + current.search === path;
  await page.goto(
    path +
      '#tgWebAppData=' +
      encodeURIComponent(initData) +
      '&tgWebAppVersion=7.10&tgWebAppPlatform=ios',
  );
  // A fragment-only goto does not reopen the Mini App or refresh its server data.
  if (sameDocument) await page.reload();
}

test('packaged application completes invitation, approval, one private club, saved profile and rejection against JDBC', async ({
  browser,
  request,
  baseURL,
}) => {
  test.setTimeout(90000);
  const owner = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  let recipient = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  const foreign = await browser.newPage({ baseURL });
  const ownerCredential = await identity(request, 'owner');
  const recipientCredential = await identity(request, 'recipient');
  const foreignCredential = await identity(request, 'foreign');
  const requests: string[] = [];
  recipient.on('request', (req) => {
    if (new URL(req.url()).pathname.startsWith('/api/'))
      requests.push(new URL(req.url()).pathname);
  });

  await open(owner, '/app?mode=platform-owner', ownerCredential);
  await expect(
    owner.getByRole('heading', { name: 'Управление платформой', exact: true }),
  ).toBeVisible();
  await owner.getByRole('button', { name: 'Пригласить клиента' }).click();
  const clientName = 'Synthetic Onboarding AB ' + Date.now();
  await owner.getByLabel('Название клиентского пространства').fill(clientName);
  await owner
    .getByLabel('Кому отправляете приглашение')
    .fill('Synthetic recipient');
  const issuedResponse = owner.waitForResponse(
    (response) =>
      response.url().endsWith('/api/platform/onboarding/invitations') &&
      response.request().method() === 'POST',
  );
  await owner.getByRole('button', { name: 'Создать ссылку' }).click();
  const issued = await (await issuedResponse).json();
  await expect(
    owner.getByRole('heading', { name: 'Приглашение создано' }),
  ).toBeVisible();
  const launch = new URL(
    await owner.locator('.ob-link').innerText(),
  ).searchParams.get('startapp')!;
  expect(launch).toMatch(/^ci_[A-Za-z0-9_-]{43}$/);
  await owner.getByRole('button', { name: 'Открыть приглашение' }).click();

  await open(recipient, '/app', await identity(request, 'recipient', launch));
  await expect(
    recipient.getByRole('heading', { name: 'Приглашение клиента' }),
  ).toBeVisible();
  await expect(recipient).toHaveURL(/mode=client/);
  expect(recipient.url()).not.toContain(launch);
  expect(requests.some((path) => path === '/api/clubs')).toBe(false);
  await recipient.getByRole('checkbox').check();
  await recipient.getByRole('button', { name: 'Подтвердить аккаунт' }).click();
  await expect(
    recipient.getByRole('heading', { name: 'Ожидаем подтверждение платформы' }),
  ).toBeVisible();
  const code = await recipient.locator('.ob-code').innerText();
  expect(code.length).toBeGreaterThanOrEqual(4);
  const cache = await recipient.evaluate(() =>
    JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }),
  );
  expect(cache).not.toContain(launch);
  expect(cache).not.toContain('tgWebAppData');

  await owner.getByRole('button', { name: 'Обновить статус' }).click();
  await expect(owner.locator('.ob-code')).toHaveText(code);
  await expect(
    owner.getByRole('button', { name: 'Предоставить доступ' }),
  ).toBeDisabled();
  await owner.getByRole('checkbox').check();
  await owner.getByRole('button', { name: 'Предоставить доступ' }).click();
  await expect(
    owner.getByText('Доступ предоставлен', { exact: true }),
  ).toBeVisible();
  await recipient.getByRole('button', { name: 'Обновить статус' }).click();
  await recipient.getByRole('button', { name: 'Открыть «Мои клубы»' }).click();
  await recipient.getByRole('button', { name: new RegExp(clientName) }).click();
  await expect(
    recipient.getByText('Клубов пока нет', { exact: true }),
  ).toBeVisible();
  await recipient.getByRole('button', { name: 'Подключить заведение' }).click();
  await recipient.getByLabel('Название заведения').fill('Synthetic Venue');
  await recipient.getByLabel('Код страны (2 буквы)').fill('RU');
  await recipient.getByLabel('Город', { exact: true }).fill('Synthetic City');
  await recipient
    .getByLabel('Адрес', { exact: true })
    .fill('Synthetic Street 1');
  await recipient.getByLabel('Часовой пояс').fill('Europe/Moscow');
  const draftResponse = recipient.waitForResponse(
    (response) =>
      response.url().endsWith('/applications') &&
      response.request().method() === 'POST',
  );
  await recipient.getByRole('button', { name: 'Сохранить черновик' }).click();
  const draft = await (await draftResponse).json();
  await expect(
    recipient.getByRole('button', { name: 'Отправить заявку' }),
  ).toBeDisabled();
  await recipient.getByRole('checkbox').check();
  await recipient.getByRole('button', { name: 'Отправить заявку' }).click();
  await expect(
    recipient.getByText('Отправлена', { exact: true }).first(),
  ).toBeVisible();
  await expect(recipient.getByLabel('Название заведения')).toHaveCount(0);

  await open(
    owner,
    '/app?mode=platform-owner&screen=application&resource=' + draft.id,
    ownerCredential,
  );
  await owner.getByRole('button', { name: 'Начать рассмотрение' }).click();
  await owner
    .getByLabel('Номер или ссылка согласования')
    .fill('SYNTHETIC-COMMERCIAL-' + draft.id);
  await owner.getByLabel('Дата согласования').fill('2026-10-28');
  await owner
    .getByLabel('Внутренняя заметка платформы')
    .fill('Synthetic private platform note');
  await owner.getByRole('checkbox').check();
  await owner.getByRole('button', { name: 'Одобрить подключение' }).click();
  await expect(
    owner.getByText('Одобрена', { exact: true }).first(),
  ).toBeVisible();

  await open(recipient, '/app?mode=client', recipientCredential);
  await recipient.getByRole('button', { name: new RegExp(clientName) }).click();
  await recipient.getByRole('button', { name: /Synthetic Venue/ }).click();
  await expect(recipient.getByText('Подключение одобрено.')).toBeVisible();
  await expect(
    recipient.getByText('Synthetic private platform note'),
  ).toHaveCount(0);
  await expect(
    recipient.getByRole('button', { name: 'Создать клуб' }),
  ).toBeVisible();
  await recipient.screenshot({
    path: test.info().outputPath('approved-client.png'),
    fullPage: true,
  });
  expect(
    await recipient.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);

  // Execute the real transaction, then lose its HTTP response. The UI must retry
  // the original command without creating a second club or a new intent.
  const creates: { key: string; body: string | null }[] = [];
  await recipient.route(
    '**/api/client/applications/' + draft.id + '/club',
    async (route) => {
      creates.push({
        key: route.request().headers()['idempotency-key'],
        body: route.request().postData(),
      });
      if (creates.length === 1) {
        const committed = await route.fetch();
        expect(committed.ok()).toBe(true);
        return route.abort();
      }
      return route.continue();
    },
  );
  await recipient.getByRole('button', { name: 'Создать клуб' }).click();
  await expect(
    recipient.getByText('Результат операции пока неизвестен'),
  ).toBeVisible();
  await expect(
    recipient.getByRole('button', { name: 'Создать клуб' }),
  ).toBeDisabled();
  const createResponse = recipient.waitForResponse(
    (result) =>
      result.url().endsWith('/applications/' + draft.id + '/club') &&
      result.ok(),
  );
  await recipient
    .getByRole('button', { name: 'Повторить ту же операцию' })
    .click();
  const created = await (await createResponse).json();
  expect(creates).toHaveLength(2);
  expect(creates[0]).toEqual(creates[1]);
  await expect(
    recipient.getByText('Непубличный', { exact: true }),
  ).toBeVisible();
  await expect(
    recipient.getByText('Бот не подключён', { exact: true }),
  ).toBeVisible();
  await expect(
    recipient.getByText('Synthetic Street 1', { exact: true }),
  ).toBeVisible();
  await expect(recipient.getByLabel('Адрес', { exact: true })).toHaveCount(0);
  await recipient
    .getByLabel('Отображаемое название')
    .fill('Synthetic Saved Club');
  await recipient
    .getByLabel('Описание клуба')
    .fill('Synthetic saved description');
  await recipient.getByLabel('Часовой пояс').fill('Europe/Paris');
  await recipient.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(recipient.getByText('Сохранено', { exact: true })).toBeVisible();
  await recipient.screenshot({
    path: test.info().outputPath('saved-private-club.png'),
    fullPage: true,
  });
  await recipient.close();
  recipient = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  await open(recipient, '/app?mode=client', recipientCredential);
  await recipient.getByRole('button', { name: new RegExp(clientName) }).click();
  await recipient.getByRole('button', { name: /Synthetic Saved Club/ }).click();
  await expect(recipient).toHaveURL(
    new RegExp('screen=club&resource=' + created.id),
  );
  await expect(recipient.getByLabel('Описание клуба')).toHaveValue(
    'Synthetic saved description',
  );
  await expect(recipient.getByLabel('Часовой пояс')).toHaveValue(
    'Europe/Paris',
  );
  const approvedSnapshot = await request.get(
    '/api/client/applications/' + draft.id,
    { headers: { 'X-Telegram-Init-Data': recipientCredential } },
  );
  const consumed = await approvedSnapshot.json();
  expect(consumed.venueName).toBe('Synthetic Venue');
  expect(consumed.timezone).toBe('Europe/Moscow');
  expect(consumed.createdClubId).toBe(created.id);
  expect(consumed.availableActions).toContain('openClub');
  expect(consumed.availableActions).not.toContain('createClub');

  await open(
    foreign,
    '/app?mode=client&screen=application&resource=' + draft.id,
    foreignCredential,
  );
  await expect(foreign.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(foreign.getByText('Synthetic Venue')).toHaveCount(0);
  await open(
    foreign,
    '/app?mode=client&screen=club&resource=' + created.id,
    foreignCredential,
  );
  await expect(foreign.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(foreign.getByText('Synthetic Street 1')).toHaveCount(0);
  const context = await request.get('/api/client/context', {
    headers: { 'X-Telegram-Init-Data': recipientCredential },
  });
  const clients = (await context.json()).clients;
  expect(
    clients.find((client: { id: string }) => client.id === issued.clientId)
      .clubs,
  ).toEqual([
    {
      id: created.id,
      name: 'Synthetic Saved Club',
      permissions: ['client.club.read'],
    },
  ]);
  await open(
    owner,
    '/app?mode=platform-owner&screen=client&resource=' + issued.clientId,
    ownerCredential,
  );
  owner.once('dialog', (dialog) => dialog.accept());
  await owner
    .getByRole('button', { name: 'Отозвать доступ', exact: true })
    .click();
  await expect(
    owner.getByText('Доступ отозван', { exact: true }),
  ).toBeVisible();
  // Same SDK identity and same page: pageshow must invalidate the formerly private projection.
  await recipient.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  await expect(recipient.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(
    recipient.getByRole('heading', { name: 'Synthetic Saved Club' }),
  ).toHaveCount(0);
  await owner
    .getByRole('checkbox', { name: 'Управление заявками', exact: true })
    .check();
  await owner
    .getByRole('checkbox', {
      name: /Восстановить доступ этому же Telegram-аккаунту/,
    })
    .check();
  await owner
    .getByRole('button', { name: 'Восстановить выбранный доступ' })
    .click();
  await expect(
    owner.getByText('Доступ активен', { exact: true }),
  ).toBeVisible();
  await recipient.evaluate(() => window.dispatchEvent(new Event('pageshow')));
  // Restoring manage alone must not restore either club grant.
  await expect(recipient.getByText(/Доступ утрачен/)).toBeVisible();
  await open(
    recipient,
    '/app?mode=client&screen=application&resource=' + draft.id,
    recipientCredential,
  );
  await expect(recipient.getByText('Подключение одобрено.')).toBeVisible();
  await expect(
    recipient.getByRole('button', { name: 'Открыть клуб' }),
  ).toHaveCount(0);
  await expect(
    recipient.getByRole('button', { name: 'Создать клуб' }),
  ).toHaveCount(0);

  await open(recipient, '/app?mode=client', recipientCredential);
  await recipient.getByRole('button', { name: new RegExp(clientName) }).click();
  await recipient.getByRole('button', { name: 'Подключить заведение' }).click();
  await recipient
    .getByLabel('Название заведения')
    .fill('Synthetic Second Venue');
  await recipient.getByLabel('Код страны (2 буквы)').fill('RU');
  await recipient.getByLabel('Город', { exact: true }).fill('Synthetic City');
  await recipient
    .getByLabel('Адрес', { exact: true })
    .fill('Synthetic Street 2');
  await recipient.getByLabel('Часовой пояс').fill('Europe/Moscow');
  const secondDraftResponse = recipient.waitForResponse(
    (response) =>
      response.url().endsWith('/applications') &&
      response.request().method() === 'POST',
  );
  await recipient.getByRole('button', { name: 'Сохранить черновик' }).click();
  const secondDraft = await (await secondDraftResponse).json();
  expect(secondDraft.id).not.toBe(draft.id);
  expect(secondDraft.previousApplicationId).toBeNull();
  await recipient.getByRole('checkbox').check();
  await recipient.getByRole('button', { name: 'Отправить заявку' }).click();
  await expect(
    recipient.getByText('Отправлена', { exact: true }).first(),
  ).toBeVisible();
  await open(
    owner,
    '/app?mode=platform-owner&screen=application&resource=' + secondDraft.id,
    ownerCredential,
  );
  await owner.getByRole('button', { name: 'Начать рассмотрение' }).click();
  await expect(
    owner.getByText('На рассмотрении', { exact: true }).first(),
  ).toBeVisible();
  const rejectionReason = 'Synthetic venue address requires correction';
  await owner.getByLabel('Причина / сообщение клиенту').fill(rejectionReason);
  owner.once('dialog', (dialog) => dialog.accept());
  await owner.getByRole('button', { name: 'Отклонить заявку' }).click();
  await expect(
    owner.getByText('Отклонена', { exact: true }).first(),
  ).toBeVisible();
  await open(
    recipient,
    '/app?mode=client&screen=application&resource=' + secondDraft.id,
    recipientCredential,
  );
  await expect(
    recipient.getByText('Отклонена', { exact: true }).first(),
  ).toBeVisible();
  await expect(recipient.getByText(rejectionReason).first()).toBeVisible();
  await expect(recipient.getByLabel('Название заведения')).toHaveCount(0);
  await expect(
    recipient.getByRole('button', { name: 'Отправить заявку' }),
  ).toHaveCount(0);
  await expect(
    recipient.getByRole('button', { name: 'Отменить заявку' }),
  ).toHaveCount(0);
  await recipient.getByRole('button', { name: 'Подать заново' }).click();
  await expect(
    recipient.getByText(
      'Новая заявка связана с предыдущей №' + secondDraft.id + '.',
    ),
  ).toBeVisible();
  await expect(recipient.getByLabel('Название заведения')).toHaveValue(
    'Synthetic Second Venue',
  );
  await recipient
    .getByLabel('Адрес', { exact: true })
    .fill('Synthetic Corrected Street 2');
  const linkedDraftResponse = recipient.waitForResponse(
    (response) =>
      response.url().endsWith('/applications') &&
      response.request().method() === 'POST',
  );
  await recipient.getByRole('button', { name: 'Сохранить черновик' }).click();
  const linkedDraft = await (await linkedDraftResponse).json();
  expect(linkedDraft.id).not.toBe(secondDraft.id);
  expect(linkedDraft.status).toBe('draft');
  expect(linkedDraft.previousApplicationId).toBe(secondDraft.id);
  await expect(recipient.getByLabel('Адрес', { exact: true })).toHaveValue(
    'Synthetic Corrected Street 2',
  );
  await open(
    recipient,
    '/app?mode=client&screen=application&resource=' + secondDraft.id,
    recipientCredential,
  );
  await expect(
    recipient.getByText('Отклонена', { exact: true }).first(),
  ).toBeVisible();
  await expect(recipient.getByText(rejectionReason).first()).toBeVisible();
  await expect(
    recipient.getByText('Synthetic Street 2', { exact: true }),
  ).toBeVisible();
  await open(
    recipient,
    '/app?mode=client&screen=application&resource=' + draft.id,
    recipientCredential,
  );
  await expect(
    recipient.getByRole('heading', {
      name: 'Synthetic Venue',
      exact: true,
      level: 1,
    }),
  ).toBeVisible();
  await expect(recipient.getByText('Подключение одобрено.')).toBeVisible();
  await owner.close();
  await recipient.close();
  await foreign.close();
});
