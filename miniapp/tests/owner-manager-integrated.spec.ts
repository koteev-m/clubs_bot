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
  await staff
    .getByRole('button', {
      name: 'Подтвердить Telegram-аккаунт',
      exact: true,
    })
    .click();
  await expect(
    staff.getByRole('heading', {
      name: 'Ожидаем подтверждение владельца клуба',
      exact: true,
    }),
  ).toBeVisible();
  const code = (await staff.locator('.ob-code').textContent())!;
  expect(
    (
      await request.get(`/api/client/clubs/${club.id}/profile`, {
        headers: { 'X-Telegram-Init-Data': employee },
      })
    ).status(),
  ).toBe(404);
  await owner
    .getByRole('button', {
      name: 'Открыть команду и подтверждение',
      exact: true,
    })
    .click();
  await owner.getByRole('button', { name: /Проверить кандидата №/ }).click();
  await owner.getByLabel('Код из исходного личного чата').fill(code);
  await owner
    .getByRole('checkbox', {
      name: 'Сверил Telegram-аккаунт и код в исходном личном чате',
    })
    .check();
  await owner
    .getByRole('button', {
      name: 'Подтвердить изменение доступа',
      exact: true,
    })
    .click();
  await expect(
    owner.getByRole('region', { name: 'Подтверждение изменения доступа' }),
  ).toHaveCount(0);
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
    await participant
      .getByRole('button', { name: 'Подтвердить Telegram-аккаунт' })
      .click();
    await expect(
      participant.getByRole('heading', {
        name: 'Ожидаем подтверждения назначения',
      }),
    ).toBeVisible();
    const code = await participant.locator('.ob-code').innerText();
    expect(
      (
        await request.get(profileUrl, {
          headers: { 'X-Telegram-Init-Data': credential },
        })
      ).status(),
    ).toBe(404);
    await sender.evaluate(() => window.dispatchEvent(new Event('focus')));
    await sender
      .getByRole('article')
      .filter({ hasText: persona })
      .getByRole('button', { name: 'Подтвердить назначение' })
      .click();
    await sender.getByLabel('Код из Telegram получателя').fill(code);
    await sender
      .getByRole('button', { name: 'Назначить управляющего', exact: true })
      .click();
    await expect(sender.getByText('Сохранено', { exact: true })).toBeVisible();
    await participant.getByRole('button', { name: 'Обновить статус' }).click();
    await expect(
      participant.getByRole('button', { name: 'Открыть кабинет' }),
    ).toBeVisible();
    const fresh = await identity(request, persona);
    await participant.getByRole('button', { name: 'Открыть кабинет' }).click();
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
