import { randomUUID } from 'node:crypto';
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from '@playwright/test';
import { contrast, telegramTheme } from './telegram-theme';
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
) {
  const response = body
    ? await request.post(path, {
        headers: {
          'X-Telegram-Init-Data': credential,
          'Idempotency-Key': randomUUID(),
        },
        data: body,
      })
    : await request.get(path, {
        headers: { 'X-Telegram-Init-Data': credential },
      });
  expect(response.status()).toBe(200);
  return response.json();
}
async function open(page: Page, credential: string, path = '/app?mode=client') {
  await page.goto(
    path +
      '#' +
      new URLSearchParams({
        tgWebAppData: credential,
        tgWebAppVersion: '7.10',
        tgWebAppPlatform: 'ios',
      }),
  );
}
async function confirm(owner: Page, code: string) {
  await owner.getByRole('button', { name: /Проверить кандидата №/ }).click();
  await owner.getByLabel('Код из исходного личного чата').fill(code);
  await owner
    .getByRole('checkbox', {
      name: 'Сверил Telegram-аккаунт и код в исходном личном чате',
    })
    .check();
  await owner
    .getByRole('button', { name: 'Подтвердить изменение доступа', exact: true })
    .click();
  await expect(
    owner.getByRole('region', { name: 'Подтверждение изменения доступа' }),
  ).toHaveCount(0);
}
async function invite(owner: Page, label: string) {
  await owner.getByLabel('Подпись получателя').fill(label);
  await owner
    .getByRole('button', { name: 'Создать приглашение', exact: true })
    .click();
  const link = await owner.getByLabel('Ссылка приглашения').inputValue();
  await owner
    .getByRole('button', { name: 'Скопировать ссылку', exact: true })
    .click();
  await expect(
    owner.getByText(/Ссылка скопирована|Выделите ссылку и скопируйте/),
  ).toBeVisible();
  const startParam = new URL(link).searchParams.get('startapp')!;
  expect(startParam).toMatch(/^ci_[A-Za-z0-9_-]{43}$/);
  return startParam;
}

async function selectEmployeeClub(
  page: Page,
  clientName: string,
  clubName: string,
) {
  await page.waitForFunction(
    ({ clientName, clubName }) =>
      [...document.querySelectorAll('h1')].some(
        (heading) => heading.textContent === clubName,
      ) ||
      [...document.querySelectorAll('button')].some(
        (button) =>
          button.textContent?.includes(clientName) ||
          button.textContent?.includes(clubName),
      ),
    { clientName, clubName },
  );
  if (
    await page
      .getByRole('heading', { name: clubName, exact: true, level: 1 })
      .isVisible()
  )
    return;
  const organization = page.getByRole('button', {
    name: new RegExp(clientName),
  });
  if (await organization.isVisible()) await organization.click();
  await page.getByRole('button', { name: new RegExp(clubName) }).click();
}

test('owner team packaged journey binds the employee and scopes role changes disable and fresh admission', async ({
  browser,
  request,
  baseURL,
}) => {
  test.setTimeout(120000);
  const platform = await identity(request, 'owner');
  const ownerCredential = await identity(request, 'recipient');
  const name = 'Staff network ' + Date.now();
  const issued = await api(
    request,
    platform,
    '/api/platform/onboarding/invitations',
    { clientName: name, recipientLabel: 'Synthetic owner', allowedClubs: 2 },
  );
  await api(
    request,
    ownerCredential,
    `/api/client/invitations/${issued.id}/claim`,
    { token: issued.token, expectedVersion: issued.version },
  );
  const create = (venueName: string) =>
    api(
      request,
      ownerCredential,
      `/api/client/clients/${issued.clientId}/clubs`,
      {
        venueName,
        countryCode: 'RU',
        cityId: '524901',
        address: 'Synthetic ' + venueName,
      },
    );
  const club = await create('Team A1 ' + Date.now());
  const second = await create('Team A2 ' + Date.now());
  const owner = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  let employee = await browser.newPage({
    baseURL,
    viewport: { width: 320, height: 640 },
  });
  await open(owner, ownerCredential);
  await owner.waitForFunction(
    (label) =>
      location.search.includes('screen=client-space') ||
      [...document.querySelectorAll('button')].some((button) =>
        button.textContent?.includes(label),
      ),
    name,
  );
  if (!owner.url().includes('screen=client-space'))
    await owner.getByRole('button', { name: new RegExp(name) }).click();
  await owner.getByRole('button', { name: new RegExp(club.name) }).click();
  await owner
    .getByRole('button', { name: 'Команда клуба', exact: true })
    .click();
  await expect(owner).toHaveURL(new RegExp(`screen=team&resource=${club.id}`));
  await telegramTheme(owner, 'dark');
  await expect(owner.getByLabel('Подпись получателя')).toBeVisible();
  await owner.getByLabel('Роль нового сотрудника').selectOption('CLUB_ADMIN');
  await owner.screenshot({
    path: test.info().outputPath('team-dark.png'),
    fullPage: true,
  });
  await owner.setViewportSize({ width: 320, height: 640 });
  await telegramTheme(owner, 'light');
  expect(
    await owner.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await owner.screenshot({
    path: test.info().outputPath('team-light-320.png'),
    fullPage: true,
  });
  await owner
    .getByLabel('Роль нового сотрудника')
    .selectOption('ENTRY_MANAGER');
  await owner.setViewportSize({ width: 390, height: 844 });

  const parameter = await invite(owner, 'Synthetic employee');
  let employeeCredential = await identity(request, 'staff', parameter);
  await open(employee, employeeCredential);
  await employee
    .getByRole('button', { name: 'Подтвердить Telegram-аккаунт', exact: true })
    .click();
  await expect(
    employee.getByRole('heading', {
      name: 'Ожидаем подтверждение владельца клуба',
    }),
  ).toBeVisible();
  const code = (await employee.locator('.ob-code').textContent())!;
  expect(
    (
      await request.get(`/api/client/clubs/${club.id}/profile`, {
        headers: { 'X-Telegram-Init-Data': employeeCredential },
      })
    ).status(),
  ).toBe(404);
  await owner.evaluate(() => window.dispatchEvent(new Event('focus')));
  await confirm(owner, code);
  await employee
    .getByRole('button', { name: 'Обновить статус', exact: true })
    .click();
  await employee
    .getByRole('button', { name: 'Открыть кабинет', exact: true })
    .click();
  await selectEmployeeClub(employee, name, club.name);
  await expect(
    employee.getByRole('heading', { name: club.name, exact: true, level: 1 }),
  ).toBeVisible();
  await expect(
    employee.getByText('Профиль доступен только для просмотра.', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    employee.getByRole('button', { name: 'Финансовые настройки', exact: true }),
  ).toHaveCount(0);
  await expect(
    employee.getByRole('button', { name: 'Команда клуба', exact: true }),
  ).toHaveCount(0);
  expect(
    (
      await request.get(`/api/client/clubs/${second.id}/profile`, {
        headers: { 'X-Telegram-Init-Data': employeeCredential },
      })
    ).status(),
  ).toBe(404);
  const team = await api(
    request,
    ownerCredential,
    `/api/client/clubs/${club.id}/team`,
  );
  const staff = team.members.find(
    (member: { role: string | null }) => member.role === 'ENTRY_MANAGER',
  );
  await owner
    .getByRole('button', {
      name: `Изменить роль №${staff.userId}`,
      exact: true,
    })
    .click();
  await owner.getByLabel('Новая роль').selectOption('MANAGER');
  await owner
    .getByLabel('Причина изменения доступа')
    .fill('Synthetic responsibilities');
  await owner
    .getByRole('checkbox', {
      name: 'Проверил пакет прав и подтверждаю изменение',
    })
    .check();
  await owner
    .getByRole('button', { name: 'Подтвердить изменение доступа', exact: true })
    .click();
  await expect(
    owner.getByRole('region', { name: 'Подтверждение изменения доступа' }),
  ).toHaveCount(0);
  await employee.close();
  employee = await browser.newPage({
    baseURL,
    viewport: { width: 320, height: 640 },
  });
  employeeCredential = await identity(request, 'staff');
  await open(employee, employeeCredential);
  await selectEmployeeClub(employee, name, club.name);
  await expect(
    employee.getByRole('button', { name: 'Сохранить профиль', exact: true }),
  ).toBeVisible();
  await employee
    .getByRole('textbox', { name: 'Описание клуба', exact: true })
    .fill('Saved by scoped manager');
  await employee
    .getByRole('button', { name: 'Сохранить профиль', exact: true })
    .click();
  await expect(employee.getByText('Сохранено', { exact: true })).toBeVisible();
  const saved = await api(
    request,
    employeeCredential,
    `/api/client/clubs/${club.id}/profile`,
  );
  expect(saved.description).toBe('Saved by scoped manager');
  await telegramTheme(employee, 'light');
  expect(
    await contrast(
      employee.getByRole('heading', { name: club.name, exact: true, level: 1 }),
    ),
  ).toBeGreaterThanOrEqual(4.5);
  expect(
    await employee.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await owner
    .getByRole('button', {
      name: `Отключить доступ №${staff.userId}`,
      exact: true,
    })
    .click();
  await owner.getByLabel('Причина изменения доступа').fill('Synthetic revoke');
  await owner
    .getByRole('checkbox', {
      name: 'Проверил пакет прав и подтверждаю изменение',
    })
    .check();
  await owner
    .getByRole('button', { name: 'Подтвердить изменение доступа', exact: true })
    .click();
  await expect(
    owner.getByRole('region', { name: 'Подтверждение изменения доступа' }),
  ).toHaveCount(0);
  await employee.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(employee.getByText(/Доступ утрачен/)).toBeVisible();
  expect(
    (
      await request.post('/api/client/invitations/resolve', {
        headers: { 'X-Telegram-Init-Data': employeeCredential },
        data: { token: parameter.slice(3) },
      })
    ).status(),
  ).toBe(404);
  const replacement = await invite(owner, 'Synthetic readmission');
  await employee.close();
  employee = await browser.newPage({
    baseURL,
    viewport: { width: 320, height: 640 },
  });
  employeeCredential = await identity(request, 'staff', replacement);
  await open(employee, employeeCredential);
  await employee
    .getByRole('button', { name: 'Подтвердить Telegram-аккаунт', exact: true })
    .click();
  const newCode = (await employee.locator('.ob-code').textContent())!;
  await owner.evaluate(() => window.dispatchEvent(new Event('focus')));
  await confirm(owner, newCode);
  const restored = await api(
    request,
    employeeCredential,
    `/api/client/clubs/${club.id}/profile`,
  );
  expect(restored.permissions).toEqual(['client.club.read']);
  await owner
    .getByRole('button', { name: 'Назад к клубу', exact: true })
    .click();
  await expect(
    owner.getByRole('heading', { name: club.name, exact: true, level: 1 }),
  ).toBeVisible();
  await owner.close();
  await employee.close();
});
