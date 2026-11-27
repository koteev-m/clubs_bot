import { randomUUID } from 'node:crypto';
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from '@playwright/test';
import { telegramTheme, contrast } from './telegram-theme';
async function identity(
  request: APIRequestContext,
  persona: string,
  startParam?: string,
) {
  const response = startParam
    ? await request.post('/fixture/identity/' + persona, {
        data: { startParam },
      })
    : await request.get('/fixture/identity/' + persona);
  expect(response.ok()).toBe(true);
  return (await response.json()).initData as string;
}
async function api(
  request: APIRequestContext,
  credential: string,
  path: string,
  body?: object,
  method = 'POST',
) {
  const response = await request.fetch(path, {
    method: body ? method : 'GET',
    headers: {
      'X-Telegram-Init-Data': credential,
      ...(body ? { 'Idempotency-Key': randomUUID() } : {}),
    },
    ...(body ? { data: body } : {}),
  });
  expect(response.status(), path).toBe(200);
  return response.json();
}
async function open(page: Page, credential: string, path: string) {
  const sameDocument =
    page.url().startsWith('http') &&
    new URL(page.url()).pathname + new URL(page.url()).search === path;
  await page.goto(
    path +
      '#' +
      new URLSearchParams({
        tgWebAppData: credential,
        tgWebAppVersion: '7.10',
        tgWebAppPlatform: 'ios',
      }),
  );
  // Reopening the same Telegram location must refresh the document, not only its fragment.
  if (sameDocument) await page.reload();
}
async function createClub(
  page: Page,
  credential: string,
  clientId: string,
  name: string,
) {
  await open(
    page,
    credential,
    `/app?mode=client&screen=new-club&resource=${clientId}`,
  );
  await page.getByLabel('Название заведения').fill(name);
  await page.getByLabel('Страна', { exact: true }).selectOption('RU');
  await page.getByLabel('Город', { exact: true }).fill('Москва');
  await page.getByLabel('Город и регион').selectOption('524901');
  await page.getByLabel('Полный адрес').fill('Синтетический адрес ' + name);
  const result = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/client/clients/${clientId}/clubs`) &&
      response.request().method() === 'POST' &&
      response.ok(),
  );
  await page
    .getByRole('button', { name: 'Создать заведение', exact: true })
    .click();
  const club = await (await result).json();
  await expect(
    page.getByRole('heading', {
      name,
      exact: true,
    }),
  ).toBeVisible();
  return club as { id: string; clientId: string; name: string };
}
async function expectCreationDenied(request: APIRequestContext, credential: string, clientId: string) {
  const denied = await request.post('/api/client/clients/' + clientId + '/clubs', {
    headers: { 'X-Telegram-Init-Data': credential, 'Idempotency-Key': randomUUID() },
    data: { venueName: 'Без подтверждения', countryCode: 'RU', cityId: '524901', address: 'Тестовая улица, 5' },
  });
  expect(denied.status()).toBe(404);
}
async function shot(page: Page, name: string) {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(await page.locator('.po-main').evaluate((node) => node.scrollWidth <= node.clientWidth)).toBe(true);
  await page.evaluate(() => {
    window.scrollTo(0, 0);
    document.querySelector('main')?.scrollTo(0, 0);
  });
  await page.screenshot({
    path: test.info().outputPath(name),
    fullPage: true,
  });
}
test('upgraded clubs keep independent setup and preparation access, while creating another club requires owner confirmation', async ({
  browser,
  request,
  baseURL,
}) => {
  test.setTimeout(180000);
  const creator = await identity(request, 'recipient');
  const history = await request.get(
    '/fixture/historical-clubs/preparation-' + browser.browserType().name(),
  );
  expect(history.ok()).toBe(true);
  const { clientId, clubs: [personal, club] } = await history.json();
  const owner = await browser.newPage({
    baseURL,
    viewport: { width: 320, height: 740 },
    isMobile: true,
    hasTouch: true,
  });
  const staff = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  });
  await open(owner, creator, '/app?mode=client&screen=client-space&resource=' + clientId);
  await expect(owner.getByText(/Для создания нового заведения/)).toBeVisible();
  await expect(owner.getByRole('button', { name: 'Создать заведение' })).toHaveCount(0);
  await expect(owner.getByRole('button', { name: new RegExp(personal.name) })).toBeVisible();
  await expectCreationDenied(request, creator, clientId);
  await open(owner, creator, '/app?mode=client&screen=preparation-choice&resource=' + personal.id);
  await telegramTheme(owner, 'light');
  await shot(owner, 'creation-choice-light-320.png');
  await owner
    .getByRole('button', { name: 'Настроить самостоятельно', exact: true })
    .click();
  await expect(
    owner.getByRole('heading', { name: 'Настройки клуба', exact: true }),
  ).toBeVisible();
  await owner.getByRole('button', { name: /^Профиль Название/ }).click();
  await owner
    .getByLabel('Описание клуба')
    .fill('Сохранённый профиль самостоятельной подготовки');
  await owner
    .getByRole('button', { name: 'Сохранить профиль', exact: true })
    .click();
  await expect(owner.getByText('Сохранено', { exact: true })).toBeVisible();
  await open(
    owner,
    creator,
    `/app?mode=client&screen=club&resource=${personal.id}`,
  );
  await expect(
    owner.getByText('Подготовка в работе', { exact: true }),
  ).toBeVisible();
  await owner
    .getByRole('button', { name: 'Передать на проверку', exact: true })
    .click();
  await expect(
    owner.getByText('Отправлено на проверку', { exact: true }),
  ).toBeVisible();
  await owner
    .getByRole('button', { name: 'Принять подготовку', exact: true })
    .click();
  await expect(
    owner.getByText('Принято владельцем', { exact: true }),
  ).toBeVisible();
  expect(
    (
      await api(
        request,
        creator,
        `/api/client/clubs/${personal.id}/preparation`,
      )
    ).published,
  ).toBe(false);

  await open(owner, creator, '/app?mode=client&screen=preparation-choice&resource=' + club.id);
  await owner
    .getByRole('button', {
      name: 'Назначить ответственного за подготовку',
      exact: true,
    })
    .click();
  await owner
    .getByLabel('Имя приглашённого ответственного')
    .fill('Анна · подготовка');
  await owner
    .getByRole('button', { name: 'Пригласить ответственного', exact: true })
    .click();
  const link = await owner.getByLabel('Ссылка ответственного').inputValue();
  const parameter = new URL(link).searchParams.get('startapp')!;
  expect(parameter).toMatch(/^ci_[A-Za-z0-9_-]{43}$/);
  let employee = await identity(request, 'staff', parameter);
  await open(staff, employee, '/app');
  const acceptStaff = staff.getByRole('button', { name: 'Принять приглашение', exact: true });
  await expect(acceptStaff).toBeVisible();
  expect((await request.get(`/api/client/clubs/${club.id}/profile`, {
    headers: { 'X-Telegram-Init-Data': employee },
  })).status()).toBe(404);
  await acceptStaff.click();
  await expect(staff).not.toHaveURL(/screen=invitation/);
  employee = await identity(request, 'staff');
  await open(
    staff,
    employee,
    `/app?mode=client&screen=club&resource=${club.id}`,
  );
  await expect(
    staff.getByText('Подготовка в работе', { exact: true }),
  ).toBeVisible();
  await expect(
    staff.getByRole('button', { name: 'Команда клуба', exact: true }),
  ).toHaveCount(0);
  expect(
    (
      await request.get(`/api/client/clubs/${personal.id}/profile`, {
        headers: { 'X-Telegram-Init-Data': employee },
      })
    ).status(),
  ).toBe(404);
  const profile = await api(
    request,
    employee,
    `/api/client/clubs/${club.id}/profile`,
  );
  expect(profile.permissions).not.toContain('client.club.team.manage');
  expect(profile.permissions).not.toContain('client.application.manage');
  await telegramTheme(staff, 'dark');
  await shot(staff, 'assignee-tasks-dark-390.png');
  await staff.getByRole('button', { name: /^Профиль Название/ }).click();
  await staff
    .getByLabel('Описание клуба')
    .fill('Настройки сохранены ответственным');
  await staff
    .getByRole('button', { name: 'Сохранить профиль', exact: true })
    .click();
  await expect(staff.getByText('Сохранено', { exact: true })).toBeVisible();
  await open(
    staff,
    employee,
    `/app?mode=client&screen=calendar&resource=${club.id}`,
  );
  await staff
    .getByRole('tab', { name: 'Постоянное расписание', exact: true })
    .click();
  const friday = staff.getByRole('group', { name: 'Пятница', exact: true });
  await friday.getByRole('checkbox', { name: 'Проводим вечеринку' }).check();
  await friday
    .getByLabel('День открытия', { exact: true })
    .selectOption('next');
  await friday.getByLabel('Время открытия', { exact: true }).fill('00:00');
  await friday
    .getByLabel('День закрытия', { exact: true })
    .selectOption('same');
  await friday.getByLabel('Время закрытия', { exact: true }).fill('08:00');
  await staff
    .getByRole('button', { name: 'Сохранить расписание', exact: true })
    .click();
  await staff
    .getByRole('button', {
      name: 'Подтвердить расписание',
      exact: true,
    })
    .click();
  await expect(staff.getByText('Сохранено', { exact: true })).toBeVisible();
  const calendar = await api(
    request,
    employee,
    `/api/client/clubs/${club.id}/calendar`,
  );
  expect(
    calendar.weekly.find((row: { day: number }) => row.day === 5),
  ).toMatchObject({
    startNextDay: true,
    open: '00:00',
    close: '08:00',
    nextDay: false,
  });
  await staff.getByRole('tab', { name: 'Предстоящие ночи', exact: true }).click();
  await shot(staff, 'saved-calendar-dark-390.png');
  await open(
    staff,
    employee,
    `/app?mode=client&screen=layout&resource=${club.id}`,
  );
  await staff
    .getByRole('textbox', { name: 'Название', exact: true })
    .fill('Основной зал');
  await staff.getByRole('button', { name: 'Создать', exact: true }).click();
  await staff
    .getByRole('button', { name: 'Открыть зал', exact: true })
    .click();
  await staff
    .getByRole('button', { name: 'Добавить столы', exact: true })
    .click();
  await staff.getByLabel('Номер нового стола').fill('1');
  await staff
    .getByRole('button', { name: 'Создать стол', exact: true })
    .click();
  await expect(staff.getByRole('button', { name: /^#1 ·/ })).toBeVisible();
  await open(
    staff,
    employee,
    `/app?mode=client&screen=club&resource=${club.id}`,
  );
  await staff
    .getByRole('button', { name: 'Передать на проверку', exact: true })
    .click();
  await expect(
    staff.getByText('Отправлено на проверку', { exact: true }),
  ).toBeVisible();
  await open(
    owner,
    creator,
    `/app?mode=client&screen=club&resource=${club.id}`,
  );
  await telegramTheme(owner, 'light');
  await shot(owner, 'owner-review-light-320.png');
  await owner
    .getByRole('button', { name: 'Вернуть на доработку', exact: true })
    .click();
  await owner
    .getByLabel('Причина доработки')
    .fill('Проверьте описание клуба');
  await owner
    .getByRole('button', {
      name: 'Сохранить возврат на доработку',
      exact: true,
    })
    .click();
  await expect(
    owner.getByText('Возвращено на доработку', { exact: true }),
  ).toBeVisible();
  await open(
    staff,
    employee,
    `/app?mode=client&screen=club&resource=${club.id}`,
  );
  await expect(
    staff.getByText('Причина доработки: Проверьте описание клуба', {
      exact: true,
    }),
  ).toBeVisible();
  await staff
    .getByRole('button', { name: 'Передать на проверку', exact: true })
    .click();
  await expect(
    staff.getByText('Отправлено на проверку', { exact: true }),
  ).toBeVisible();
  await owner.evaluate(() => window.dispatchEvent(new Event('focus')));
  await owner
    .getByRole('button', { name: 'Принять подготовку', exact: true })
    .click();
  await expect(
    owner.getByText('Принято владельцем', { exact: true }),
  ).toBeVisible();
  await open(
    owner,
    creator,
    `/app?mode=client&screen=club&resource=${club.id}`,
  );
  await expect(
    owner.getByText('Принято владельцем', { exact: true }),
  ).toBeVisible();
  await expect(
    owner.getByText('Не опубликован', { exact: true }),
  ).toBeVisible();
  await expect(
    owner.getByRole('heading', { name: 'Показатели и отчёты', exact: true }),
  ).toHaveCount(0);
  await expect(
    owner.getByRole('button', {
      name: 'Открыть проверку готовности',
      exact: true,
    }),
  ).toBeVisible();
  expect(
    await contrast(owner.locator('.cm-status').first()),
  ).toBeGreaterThanOrEqual(4.5);
  await owner.setViewportSize({ width: 390, height: 844 });
  await telegramTheme(owner, 'dark');
  await shot(owner, 'owner-accepted-dark-390.png');
  await owner
    .getByRole('heading', { name: 'Готовность клуба', exact: true })
    .scrollIntoViewIfNeeded();
  await owner.screenshot({
    path: test.info().outputPath('owner-readiness-dark-390.png'),
    fullPage: true,
  });
  const team = await api(request, creator, `/api/client/clubs/${club.id}/team`);
  const member = team.members.find(
    (item: { owner: boolean; role: string }) =>
      !item.owner && item.role === 'CLUB_ADMIN',
  );
  await api(request, creator, `/api/client/clubs/${club.id}/team`, {
    action: 'disable',
    expectedVersion: team.version,
    userId: member.userId,
    expectedMemberVersion: member.version,
    reason: 'Синтетический отзыв',
    confirmed: true,
  });
  await staff.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    staff.getByText(/Доступ утрачен или запись недоступна/).first(),
  ).toBeVisible();
  await expect(
    staff.getByRole('button', { name: 'Передать на проверку', exact: true }),
  ).toHaveCount(0);
  const denied = await request.patch(
    `/api/client/clubs/${club.id}/preparation`,
    {
      headers: {
        'X-Telegram-Init-Data': employee,
        'Idempotency-Key': randomUUID(),
      },
      data: { action: 'submit', expectedVersion: '1' },
    },
  );
  expect(denied.status()).toBe(404);
  await owner.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    owner.getByText(/Доступ ответственного отозван/),
  ).toBeVisible();
  expect(
    (await api(request, creator, `/api/client/clubs/${club.id}/preparation`))
      .published,
  ).toBe(false);
  await owner.close();
  await staff.close();
});

test('packaged no-op unknown resolves a durable receipt without mutation or a calendar-clock prerequisite', async ({
  page,
  request,
}) => {
  const creator = await identity(request, 'recipient');
  const history = await request.get(
    '/fixture/historical-clubs/receipt-' + page.context().browser()!.browserType().name(),
  );
  expect(history.ok()).toBe(true);
  const { clubs: [club] } = await history.json();
  const path = `/api/client/clubs/${club.id}/calendar`;
  const before = await api(request, creator, path);
  const writes: { body: string | null; key: string | undefined }[] = [];
  const reads: { body: string | null; key: string | undefined }[] = [];
  await page.route('**' + path, async (route) => {
    if (route.request().method() !== 'PATCH') return route.continue();
    writes.push({
      body: route.request().postData(),
      key: route.request().headers()['idempotency-key'],
    });
    const committed = await route.fetch();
    expect(committed.status()).toBe(200);
    expect((await committed.json()).version).toBe(before.version);
    await route.abort('connectionreset');
  });
  await page.route('**' + path + '/commands/resolve', (route) => {
    reads.push({
      body: route.request().postData(),
      key: route.request().headers()['idempotency-key'],
    });
    return route.continue();
  });
  await open(
    page,
    creator,
    `/app?mode=client&screen=calendar&resource=${club.id}`,
  );
  await page
    .getByRole('button', { name: 'Сформировать ближайшие ночи', exact: true })
    .click();
  await page
    .getByRole('button', { name: 'Подтвердить формирование', exact: true })
    .click();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toBeVisible();
  await page.clock.setFixedTime(new Date(Date.now() + 24 * 60 * 60 * 1000));
  await page
    .getByRole('button', { name: 'Проверить результат операции', exact: true })
    .click();
  await expect(
    page.getByText('Операция выполнена. Показано актуальное расписание.', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByText('Результат операции пока неизвестен', { exact: true }),
  ).toHaveCount(0);
  expect(writes).toHaveLength(1);
  expect(reads).toHaveLength(1);
  expect(reads[0]).toEqual(writes[0]);
  const after = await api(request, creator, path);
  expect(after.version).toBe(before.version);
  expect(after.nights).toEqual(before.nights);
  expect(after.reconciliation.storedDates).toEqual(
    before.reconciliation.storedDates,
  );
  await shot(page, 'readonly-durable-receipt-completed.png');
  await page.clock.setFixedTime(new Date());
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Календарь клуба', exact: true }),
  ).toHaveCount(0);
});

test('verified business owner assigns a network manager who assigns and revokes a club manager', async ({
  browser,
  request,
  baseURL,
}) => {
  test.setTimeout(120_000);
  const platform = await identity(request, 'owner');
  const owner = await identity(request, 'recipient');
  const issued = await api(
    request,
    platform,
    '/api/platform/onboarding/invitations',
    {
      clientName: 'Бизнес ' + randomUUID().slice(0, 6),
      recipientLabel: 'Владелец бизнеса',
      allowedClubs: 2,
    },
  );
  await api(request, owner, '/api/client/invitations/' + issued.id + '/claim', {
    token: issued.token,
    expectedVersion: issued.version,
  });
  await expectCreationDenied(request, owner, issued.clientId);
  const device = {
    baseURL,
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  };
  const issuer = await browser.newPage(device);
  const network = await browser.newPage(device);
  const manager = await browser.newPage(device);
  issuer.on('dialog', (dialog) => dialog.accept());
  await open(
    issuer,
    platform,
    '/app?mode=platform-owner&screen=client&resource=' + issued.clientId,
  );
  await issuer
    .getByRole('button', { name: 'Подтвердить владельца бизнеса' })
    .click();
  await expect(
    issuer.getByText('Владелец бизнеса', { exact: true }).first(),
  ).toBeVisible();
  const club = await createClub(
    issuer,
    owner,
    issued.clientId,
    'Первая площадка',
  );
  const other = await createClub(
    issuer,
    owner,
    issued.clientId,
    'Вторая площадка',
  );
  const clubUrl = '/app?mode=client&screen=club&resource=' + club.id;
  const profileUrl = '/api/client/clubs/' + club.id + '/profile';
  await open(issuer, owner, clubUrl);
  await expect(
    issuer.getByText('Владелец бизнеса', { exact: true }),
  ).toBeVisible();
  await expect(
    issuer.getByRole('button', {
      name: 'Назначить ответственного за подготовку',
    }),
  ).toHaveCount(0);
  await issuer.getByRole('button', { name: 'Сохранённые настройки' }).click();
  await issuer.getByRole('button', { name: /Профиль Название/ }).click();
  await expect(
    issuer.getByRole('button', { name: 'Сохранить профиль' }),
  ).toHaveCount(0);
  await expect(issuer.getByLabel('Описание клуба')).toBeDisabled();
  const participate = async (
    sender: Page,
    participant: Page,
    persona: string,
    role: string,
  ) => {
    await sender
      .getByRole('button', { name: 'Управляющие', exact: true })
      .click();
    await sender.getByRole('button', { name: 'Пригласить управляющего', exact: true }).click();
    await sender.getByLabel('Имя получателя').fill(persona);
    await sender.getByLabel('Роль управляющего').selectOption(role);
    await sender.getByRole('button', { name: 'Создать приглашение' }).click();
    const link = await sender
      .getByLabel('Ссылка для управляющего')
      .inputValue();
    const start = new URL(link).searchParams.get('startapp')!;
    expect(start).toMatch(/^cm_[A-Za-z0-9_-]{43}$/);
    const credential = await identity(request, persona, start);
    await open(participant, credential, '/app');
    const accept = participant.getByRole('button', { name: 'Принять приглашение', exact: true });
    await expect(accept).toBeVisible();
    expect((await request.get(profileUrl, {
      headers: { 'X-Telegram-Init-Data': credential },
    })).status()).toBe(404);
    await accept.click();
    await expect(participant).not.toHaveURL(/screen=manager-invitation/);
    await expect(participant.locator('.ob-code')).toHaveCount(0);
    await sender.getByRole('button', { name: 'Назад', exact: true }).click();
    await expect(sender.getByRole('heading', { name: 'Управляющие', exact: true })).toBeVisible();
    const fresh = await identity(request, persona);
    await open(participant, fresh, clubUrl);
    return fresh;
  };
  await open(issuer, owner, clubUrl);
  const networkId = await participate(
    issuer,
    network,
    'staff',
    'NETWORK_MANAGER',
  );
  await expect(
    network.getByText('Управляющий сети', { exact: true }),
  ).toBeVisible();
  await network.getByRole('button', { name: /Профиль Название/ }).click();
  await network.getByLabel('Описание клуба').fill('Сохранено управляющим сети');
  await network.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(network.getByText('Сохранено', { exact: true })).toBeVisible();
  expect((await api(request, owner, profileUrl)).description).toBe('Сохранено управляющим сети');
  await network.getByRole('button', { name: 'Настройки клуба', exact: true }).click();
  await network.getByRole('button', { name: 'Назад к клубу', exact: true }).click();
  const managerId = await participate(
    network,
    manager,
    'foreign',
    'CLUB_MANAGER',
  );
  await expect(
    manager.getByText('Управляющий клуба', { exact: true }),
  ).toBeVisible();
  await expect(
    manager.getByRole('button', { name: 'Управляющие', exact: true }),
  ).toHaveCount(0);
  const clubApi = '/api/client/clubs/' + club.id;
  const layout = await api(request, managerId, clubApi + '/halls', {
    name: 'Основной зал',
  });
  const hall = layout.halls.find((value: { name: string }) => value.name === 'Основной зал');
  expect(hall).toBeDefined();
  await api(request, managerId, clubApi + '/halls/' + hall.id + '/tables', {
    label: 'Стол 7',
    tableNumber: 7,
    capacity: 6,
    expectedVersion: hall.layoutRevision,
  });
  await manager.evaluate(() => window.dispatchEvent(new Event('focus')));
  const hallOverview = manager.getByRole('region', { name: 'Обзор залов' });
  await expect(hallOverview.getByText('Основной зал')).toBeVisible();
  await expect(hallOverview.getByText('Мест за этими столами: 6')).toBeVisible();
  expect(await manager.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await hallOverview.getByRole('button', { name: 'Открыть залы и столы' }).click();
  await expect(manager.getByText('Основной зал', { exact: true })).toBeVisible();
  await open(manager, await identity(request, 'foreign'), clubUrl);
  for (const credential of [networkId, managerId]) {
    expect(
      (
        await request.get('/api/client/clubs/' + other.id + '/profile', {
          headers: { 'X-Telegram-Init-Data': credential },
        })
      ).status(),
    ).toBe(404);
  }
  await manager.getByRole('button', { name: /Профиль Название/ }).click();
  await manager
    .getByLabel('Описание клуба')
    .fill('Сохранено управляющим клуба');
  await manager.getByRole('button', { name: 'Сохранить профиль' }).click();
  await expect(manager.getByText('Сохранено', { exact: true })).toBeVisible();
  expect((await api(request, owner, profileUrl)).description).toBe(
    'Сохранено управляющим клуба',
  );
  await network.getByRole('button', { name: 'Отозвать назначение' }).click();
  await network.getByLabel('Причина отзыва').fill('Передача смены');
  await network.getByRole('button', { name: 'Подтвердить отзыв' }).click();
  await expect(
    network.getByText('Управляющие пока не назначены.'),
  ).toBeVisible();
  await manager.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    manager.getByRole('button', { name: 'Сохранить профиль' }),
  ).toHaveCount(0);
  expect(
    (
      await request.get(profileUrl, {
        headers: { 'X-Telegram-Init-Data': managerId },
      })
    ).status(),
  ).toBe(404);
  await Promise.all([issuer.close(), network.close(), manager.close()]);
});

test('mobile manager opens the floor desk after ordinary setup and records mixed tender, card and release once', async ({
  browser,
  request,
  baseURL,
}) => {
  const platform = await identity(request, 'owner');
  const owner = await identity(request, 'recipient');
  const clubManager = await identity(request, 'foreign');
  const suffix = randomUUID().slice(0, 6);
  const issued = await api(request, platform, '/api/platform/onboarding/invitations', {
    clientName: 'Операции ' + suffix,
    recipientLabel: 'Владелец бизнеса',
    allowedClubs: 1,
  });
  await api(request, owner, '/api/client/invitations/' + issued.id + '/claim', {
    token: issued.token,
    expectedVersion: issued.version,
  });
  const organization = '/api/platform/onboarding/clients/' + issued.clientId;
  const client = await api(request, platform, organization);
  const member = client.members.find((entry: { telegramUserId: string }) => entry.telegramUserId === '998102');
  expect(member).toBeDefined();
  await api(request, platform, organization + '/members/' + member.userId + '/business-owner', {
    expectedVersion: member.version,
  });
  const club = await api(request, owner, '/api/client/clients/' + issued.clientId + '/clubs', {
    venueName: 'Мобильная смена ' + suffix,
    countryCode: 'RU',
    cityId: '524901',
    address: 'Синтетический адрес',
  });
  const clubApi = '/api/client/clubs/' + club.id;
  const roster = clubApi + '/managers';
  const invite = async (sender: string, role: string) => {
    const view = await api(request, sender, roster);
    return api(request, sender, roster, {
      action: 'invite',
      expectedVersion: view.version,
      recipientLabel: 'Сотрудник',
      role,
    });
  };
  const invitation = await invite(owner, 'CLUB_MANAGER');
  const preview = await api(request, clubManager, '/api/client/manager-invitations/resolve', { token: invitation.token });
  await api(request, clubManager, '/api/client/manager-invitations/' + preview.id + '/claim', {
    token: invitation.token,
    expectedVersion: preview.version,
  });
  const floorInvite = await invite(clubManager, 'FLOOR_MANAGER');
  const layout = await api(request, clubManager, clubApi + '/halls', { name: 'Основной зал' });
  const hall = layout.halls[0];
  const mapped = await api(request, clubManager, clubApi + '/halls/' + hall.id + '/tables', {
    label: 'Стол 1',
    tableNumber: 1,
    capacity: 6,
    expectedVersion: hall.layoutRevision,
  });
  const table = mapped.halls[0].tables[0];
  const calendar = await api(request, clubManager, clubApi + '/calendar');
  const startAt = new Date(Date.now() - 5 * 60_000);
  const endAt = new Date(startAt.getTime() + 6 * 3600_000);
  const date = new Intl.DateTimeFormat('en-CA', {
    timeZone: calendar.timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  });
  const time = new Intl.DateTimeFormat('en-GB', {
    timeZone: calendar.timezone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  });
  const businessDate = date.format(startAt);
  const scheduled = await api(request, clubManager, clubApi + '/calendar', {
    expectedVersion: calendar.version,
    timezone: calendar.timezone,
    weekly: [],
    holidays: [],
    exceptions: [{
      date: businessDate,
      mode: 'open',
      open: time.format(startAt),
      close: time.format(endAt),
      nextDay: date.format(endAt) !== businessDate,
    }],
  }, 'PATCH');
  const nightId = scheduled.nights.find((night: { businessDate: string }) => night.businessDate === businessDate)?.nightId;
  expect(nightId).toBeTruthy();
  expect(table.id).toBeTruthy();
  const configuration = await api(request, clubManager, clubApi + '/deposit-configuration');
  await api(request, clubManager, clubApi + '/deposit-configuration', {
    expectedVersion: configuration.version,
    primaryName: 'Бар',
    distribution: true,
    extras: [{ name: 'Шары', active: true }],
  }, 'PATCH');

  const page = await browser.newPage({
    baseURL,
    viewport: { width: 320, height: 740 },
    isMobile: true,
    hasTouch: true,
  });
  try {
    const start = new URL(floorInvite.invitationLink).searchParams.get('startapp')!;
    const launched = await identity(request, 'second-manager', start);
    const floor = await identity(request, 'second-manager');
    const headers = { 'X-Telegram-Init-Data': floor };
    const operations = clubApi + '/operations';
    await open(page, launched, '/app');
    const accept = page.getByRole('button', { name: 'Принять приглашение', exact: true });
    await expect(accept).toBeVisible();
    expect((await request.get(operations, { headers })).status()).toBe(404);
    await accept.click();

    const desk = page.getByRole('region', { name: 'Рабочая ночь' });
    await expect(desk.getByRole('button', { name: 'Посадить гостей', exact: true })).toBeVisible();
    await expect(desk.getByText(club.name, { exact: false })).toBeVisible();
    await expect(desk.getByText('Свободен')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Перейти к настройкам' })).toHaveCount(0);
    await expect(page.getByText('Менеджер', { exact: true })).toBeVisible();
    await telegramTheme(page, 'dark');
    await shot(page, 'floor-desk-dark-320.png');

    await desk.getByRole('button', { name: 'Посадить гостей', exact: true }).click();
    await expect(desk.getByText(/Выберите свободный стол/)).toBeVisible();
    await desk.getByRole('button', { name: 'Посадить · Стол 1' }).click();
    const seat = desk.getByRole('form', { name: 'Посадка гостей' });
    await seat.getByLabel('Фактически получено').fill('15000');
    await seat.getByLabel('Шары').fill('3000');
    await seat.getByRole('radio', { name: 'Комбинированная' }).check();
    await seat.getByLabel('Наличными', { exact: true }).fill('5000');
    await expect(seat.getByText(/12\s000,00/)).toBeVisible();
    await shot(page, 'floor-cash-dark-320.png');

    const reload = page.waitForResponse((response) =>
      response.url().endsWith(operations) && response.request().method() === 'GET');
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    expect((await reload).ok()).toBe(true);
    await expect(seat.getByLabel('Фактически получено')).toHaveValue('15000');
    const location = page.url();
    const back = page.waitForEvent('dialog');
    await page.evaluate(() => {
      const telegram = window as unknown as { Telegram: { WebView: { receiveEvent: (event: string) => void } } };
      window.setTimeout(() => telegram.Telegram.WebView.receiveEvent('back_button_pressed'), 0);
    });
    const confirmation = await back;
    await confirmation.dismiss();
    await expect(page).toHaveURL(location);
    await expect(seat.getByLabel('Фактически получено')).toHaveValue('15000');

    await seat.getByRole('button', { name: 'Посадить и записать оплату' }).click();
    await expect(desk.getByText(/Записано .*наличными/i)).toBeVisible();
    await expect(desk.getByText('Занят', { exact: true })).toBeVisible();
    let shift = await api(request, floor, operations);
    expect(shift.night.id).toBe(nightId);
    expect(shift.halls[0].tables[0].payments).toMatchObject([{ amountMinor: '1500000', method: 'MIXED', cashMinor: '500000', cardMinor: '1000000' }]);

    let attempts = 0;
    let firstKey: string | undefined;
    let sameKey = false;
    await page.route('**' + operations, async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue();
        return;
      }
      attempts++;
      const key = route.request().headers()['idempotency-key'];
      if (attempts === 1) {
        firstKey = key;
        const real = await route.fetch();
        expect(real.ok()).toBe(true);
        await route.abort();
      } else {
        sameKey = Boolean(key && key === firstKey);
        await route.continue();
      }
    });
    await desk.getByRole('button', { name: 'Доплата · Стол 1' }).click();
    const topUp = desk.getByRole('form', { name: 'Доплата по посадке' });
    await expect(topUp.getByRole('radio', { name: 'Наличные' })).not.toBeChecked();
    await topUp.getByLabel('Фактически получено').fill('500');
    await topUp.getByRole('radio', { name: 'Карта' }).check();
    await topUp.getByRole('button', { name: 'Записать доплату' }).click();
    await expect(desk.getByText(/Неизвестно, сохранена ли исходная запись/)).toBeVisible();
    await open(page, floor, '/app?mode=client&screen=club&resource=' + club.id);
    await desk.getByRole('button', { name: 'Проверить исходную запись' }).click();
    await expect(desk.getByText(/Записано .*Карта/)).toBeVisible();
    expect(attempts).toBe(2);
    expect(sameKey).toBe(true);
    await page.unroute('**' + operations);
    shift = await api(request, floor, operations);
    expect(shift.halls[0].tables[0].payments).toMatchObject([
      { amountMinor: '1500000', method: 'MIXED', cashMinor: '500000', cardMinor: '1000000' },
      { amountMinor: '50000', method: 'CARD' },
    ]);

    await desk.getByRole('button', { name: 'Освободить · Стол 1' }).click();
    await desk.getByRole('button', { name: 'Освободить стол', exact: true }).click();
    await expect(desk.getByText('Стол 1 · стол освобождён')).toBeVisible();
    await expect(desk.getByText('Свободен', { exact: true })).toBeVisible();
    const released = await api(request, floor, operations);
    expect(released.halls[0].tables[0].sessionId).toBeNull();
    expect(released.halls[0].tables[0].lastSessionId).toBe(shift.halls[0].tables[0].sessionId);
  } finally {
    await page.close();
  }
});
