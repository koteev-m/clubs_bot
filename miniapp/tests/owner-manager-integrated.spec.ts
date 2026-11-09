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
    page.getByRole('heading', { name: 'Кто будет настраивать клуб?' }),
  ).toBeVisible();
  return club as { id: string; clientId: string; name: string };
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
test('private creation self setup and delegated preparation survive reopen review return acceptance and revoke', async ({
  browser,
  request,
  baseURL,
}) => {
  test.setTimeout(180000);
  const platform = await identity(request, 'owner');
  const creator = await identity(request, 'recipient');
  const issued = await api(
    request,
    platform,
    '/api/platform/onboarding/invitations',
    {
      clientName: 'Клубы Севера · ' + randomUUID().slice(0, 6),
      recipientLabel: 'Владелец',
      allowedClubs: 3,
    },
  );
  await api(request, creator, `/api/client/invitations/${issued.id}/claim`, {
    token: issued.token,
    expectedVersion: issued.version,
  });
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
  const personal = await createClub(owner, creator, issued.clientId, 'Север');
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

  const club = await createClub(
    owner,
    creator,
    issued.clientId,
    'Полярная ночь',
  );
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
  ).toBeVisible();
  expect(
    await contrast(owner.locator('.cm-status').first()),
  ).toBeGreaterThanOrEqual(4.5);
  await owner.setViewportSize({ width: 390, height: 844 });
  await telegramTheme(owner, 'dark');
  await shot(owner, 'owner-accepted-dark-390.png');
  await owner.getByRole('heading', { name: 'Показатели и отчёты', exact: true }).scrollIntoViewIfNeeded();
  await owner.screenshot({ path: test.info().outputPath('owner-results-dark-390.png'), fullPage: true });
  const team = await api(
    request,
    creator,
    `/api/client/clubs/${club.id}/team`,
  );
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
  const platform = await identity(request, 'owner');
  const creator = await identity(request, 'recipient');
  const invitation = await api(
    request,
    platform,
    '/api/platform/onboarding/invitations',
    {
      clientName: 'Receipt recovery ' + randomUUID().slice(0, 6),
      recipientLabel: 'Владелец',
      allowedClubs: 1,
    },
  );
  await api(
    request,
    creator,
    `/api/client/invitations/${invitation.id}/claim`,
    { token: invitation.token, expectedVersion: invitation.version },
  );
  const club = await createClub(
    page,
    creator,
    invitation.clientId,
    'Проверка результата',
  );
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
