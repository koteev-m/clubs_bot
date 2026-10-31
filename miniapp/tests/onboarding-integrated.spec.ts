import { expect, test, APIRequestContext, Page } from "@playwright/test";

async function identity(
  request: APIRequestContext,
  role: string,
  startParam?: string,
): Promise<string> {
  const response = startParam
    ? await request.post("/fixture/identity/" + role, { data: { startParam } })
    : await request.get("/fixture/identity/" + role);
  expect(response.ok()).toBe(true);
  return (await response.json()).initData;
}
async function open(page: Page, path: string, initData: string) {
  const current = new URL(page.url());
  const sameDocument = current.pathname + current.search === path;
  await page.goto(
    path +
      "#tgWebAppData=" +
      encodeURIComponent(initData) +
      "&tgWebAppVersion=7.10&tgWebAppPlatform=ios",
  );
  // A fragment-only goto does not reopen the Mini App or refresh its server data.
  if (sameDocument) await page.reload();
}

test("packaged JDBC journey accepts once, creates privately under limit, reopens and raises capacity", async ({
  browser,
  request,
  baseURL,
}) => {
  test.setTimeout(90000);
  const owner = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  const recipient = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  const foreign = await browser.newPage({ baseURL });
  const ownerCredential = await identity(request, "owner");
  const recipientCredential = await identity(request, "recipient");
  const foreignCredential = await identity(request, "foreign");
  const paths: string[] = [];
  recipient.on("request", (req) => {
    if (new URL(req.url()).pathname.startsWith("/api/"))
      paths.push(new URL(req.url()).pathname);
  });
  await open(owner, "/app", ownerCredential);
  await expect(
    owner.getByRole("heading", { name: "Управление платформой", exact: true }),
  ).toBeVisible();
  await owner.getByRole("button", { name: "Пригласить клиента" }).click();
  const clientName = "Synthetic simplified " + Date.now();
  await owner.getByLabel("Название клиента или компании").fill(clientName);
  await owner.getByLabel("Имя получателя").fill("Synthetic recipient");
  await expect(owner.getByLabel("Разрешено заведений")).toHaveValue("1");
  const issueResponse = owner.waitForResponse(
    (r) => r.url().endsWith("/api/platform/onboarding/invitations") && r.ok(),
  );
  await owner.getByRole("button", { name: "Создать ссылку" }).click();
  const issued = await (await issueResponse).json();
  const link = new URL(await owner.locator(".ob-link").innerText());
  const launch = link.searchParams.get("startapp")!;
  expect(launch).toMatch(/^ci_[A-Za-z0-9_-]{43}$/);
  await open(recipient, "/app", await identity(request, "recipient", launch));
  await expect(
    recipient.getByRole("heading", { name: "Приглашение клиента" }),
  ).toBeVisible();
  await expect(recipient.getByRole("checkbox")).toHaveCount(0);
  await recipient.getByRole("button", { name: "Принять приглашение" }).click();
  await expect(recipient.locator(".ob-code")).toHaveCount(0);
  await expect(recipient.getByText("Разрешено всего: 1")).toBeVisible();
  await recipient
    .getByRole("button", { name: "Создать заведение", exact: true })
    .click();
  await recipient
    .getByLabel("Название заведения")
    .fill("Synthetic First Venue");
  await recipient
    .getByLabel("Страна", { exact: true })
    .selectOption({ label: "Россия" });
  await recipient.getByLabel("Город", { exact: true }).fill("Москва");
  await recipient.getByLabel("Город и регион").selectOption("524901");
  await recipient
    .getByLabel("Полный адрес")
    .fill("Synthetic Street 1, помещение 2");
  await expect(recipient.getByLabel("Часовой пояс")).toHaveCount(0);
  await expect(
    recipient.getByRole("button", { name: "Отправить заявку" }),
  ).toHaveCount(0);
  const commands: { key: string; body: string | null }[] = [];
  await recipient.route(
    "**/api/client/clients/" + issued.clientId + "/clubs",
    async (route) => {
      commands.push({
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
      });
      if (commands.length === 1) {
        const response = await route.fetch();
        expect(response.ok()).toBe(true);
        return route.abort();
      }
      return route.continue();
    },
  );
  await recipient
    .getByRole("button", { name: "Создать заведение", exact: true })
    .click();
  await expect(
    recipient.getByText("Результат операции пока неизвестен"),
  ).toBeVisible();
  await recipient.goBack();
  await recipient.goForward();
  await expect(recipient.getByLabel("Название заведения")).toHaveValue(
    "Synthetic First Venue",
  );
  await recipient.evaluate(() => window.dispatchEvent(new Event("pageshow")));
  await expect(
    recipient.getByText("Результат операции пока неизвестен"),
  ).toBeVisible();
  const replay = recipient.waitForResponse(
    (r) => r.url().endsWith("/clients/" + issued.clientId + "/clubs") && r.ok(),
  );
  await recipient
    .getByRole("button", { name: "Повторить ту же операцию" })
    .click();
  const firstClub = await (await replay).json();
  expect(commands).toHaveLength(2);
  expect(commands[0]).toEqual(commands[1]);
  await expect(
    recipient.getByText("Непубличный", { exact: true }),
  ).toBeVisible();
  await expect(
    recipient.getByText("Synthetic Street 1, помещение 2", { exact: true }),
  ).toBeVisible();
  expect(firstClub.timezone).toBe("Europe/Moscow");
  await recipient.screenshot({
    path: test.info().outputPath("saved-private-venue.png"),
    fullPage: true,
  });
  await open(recipient, "/app", recipientCredential);
  await expect(
    recipient.getByRole("button", { name: /Synthetic First Venue/ }),
  ).toBeVisible();
  await expect(recipient.getByText("Можно добавить: 0")).toBeVisible();
  const body = {
    venueName: "Synthetic Second Venue",
    countryCode: "RU",
    cityId: "524901",
    address: "Synthetic Street 2",
  };
  const denied = await request.post(
    "/api/client/clients/" + issued.clientId + "/clubs",
    {
      headers: {
        "X-Telegram-Init-Data": recipientCredential,
        "Idempotency-Key": "synthetic-denied-second",
      },
      data: body,
    },
  );
  expect(denied.status()).toBe(409);
  expect((await denied.json()).code).toBe("club_limit_reached");
  await open(
    owner,
    "/app?mode=platform-owner&screen=client&resource=" + issued.clientId,
    ownerCredential,
  );
  await owner.getByLabel("Разрешено всего", { exact: true }).fill("2");
  await owner.getByRole("button", { name: "Сохранить лимит" }).click();
  await expect(owner.getByText("Разрешено всего: 2")).toBeVisible();
  await open(recipient, "/app", recipientCredential);
  await recipient
    .getByRole("button", { name: "Создать заведение", exact: true })
    .click();
  await recipient
    .getByLabel("Название заведения")
    .fill("Synthetic Second Venue");
  await recipient.getByLabel("Страна", { exact: true }).selectOption("RU");
  await recipient.getByLabel("Город", { exact: true }).fill("Москва");
  await recipient.getByLabel("Город и регион").selectOption("524901");
  await recipient.getByLabel("Полный адрес").fill("Synthetic Street 2");
  await recipient
    .getByRole("button", { name: "Создать заведение", exact: true })
    .click();
  await expect(
    recipient.getByRole("heading", {
      name: "Synthetic Second Venue",
      exact: true,
    }),
  ).toBeVisible();
  await open(
    foreign,
    "/app?mode=client&screen=club&resource=" + firstClub.id,
    foreignCredential,
  );
  await expect(foreign.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(
    foreign.getByText("Synthetic Street 1, помещение 2"),
  ).toHaveCount(0);
  const context = await request.get("/api/client/context", {
    headers: { "X-Telegram-Init-Data": recipientCredential },
  });
  expect(
    (await context.json()).clients.find(
      (c: { id: string }) => c.id === issued.clientId,
    ).clubs,
  ).toHaveLength(2);
  expect(paths).not.toContain("/api/clubs");
  expect(
    paths.some((path) =>
      /\/applications(?:$|\/[^/]+\/(submit|approve|club)$)/.test(path),
    ),
  ).toBe(false);
  owner.once("dialog", (dialog) => dialog.accept());
  await owner
    .getByRole("button", { name: "Отозвать доступ", exact: true })
    .click();
  await expect(
    owner.getByText("Доступ отозван", { exact: true }),
  ).toBeVisible();
  await recipient.evaluate(() => window.dispatchEvent(new Event("pageshow")));
  await expect(recipient.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(
    recipient.getByRole("heading", {
      name: "Synthetic Second Venue",
      exact: true,
    }),
  ).toHaveCount(0);
  for (const page of [owner, recipient]) {
    const stored = await page.evaluate(() =>
      JSON.stringify({
        local: { ...localStorage },
        session: { ...sessionStorage },
      }),
    );
    expect(stored).not.toContain(launch);
    expect(stored).not.toContain(recipientCredential);
  }
  await owner.close();
  await recipient.close();
  await foreign.close();
});

test("lost committed invitation response replays one client and requires explicit safe reissue before recipient claim", async ({
  browser,
  request,
  baseURL,
}) => {
  const owner = await browser.newPage({ baseURL });
  const recipient = await browser.newPage({ baseURL });
  const ownerCredential = await identity(request, "owner");
  const recipientCredential = await identity(request, "recipient");
  const clientName = "Synthetic Lost Invitation " + Date.now();
  const commands: { key: string; body: string | null }[] = [];
  let committed:
    | { id: string; clientId: string; invitationLink: string }
    | undefined;
  await owner.route("**/api/platform/onboarding/invitations", async (route) => {
    commands.push({
      key: route.request().headers()["idempotency-key"],
      body: route.request().postData(),
    });
    if (commands.length === 1) {
      const response = await route.fetch();
      expect(response.ok()).toBe(true);
      committed = await response.json();
      return route.abort();
    }
    return route.continue();
  });
  await open(owner, "/app?mode=platform-owner", ownerCredential);
  await owner.getByRole("button", { name: "Пригласить клиента" }).click();
  await owner.getByLabel("Название клиента или компании").fill(clientName);
  await owner.getByLabel("Имя получателя").fill("Synthetic lost recipient");
  await owner.getByRole("button", { name: "Создать ссылку" }).click();
  await expect(
    owner.getByText("Ответ сервера не получен, результат нужно проверить."),
  ).toBeVisible();
  await owner.goBack();
  await expect(
    owner.getByRole("heading", {
      name: "Управление платформой",
      exact: true,
    }),
  ).toBeVisible();
  await owner.goForward();
  await expect(owner.getByLabel("Название клиента или компании")).toHaveValue(
    clientName,
  );
  await expect(
    owner.getByRole("button", { name: "Создать ссылку" }),
  ).toBeDisabled();
  const replayResponse = owner.waitForResponse(
    (response) =>
      response.url().endsWith("/api/platform/onboarding/invitations") &&
      response.ok(),
  );
  await owner.getByRole("button", { name: "Проверить результат" }).click();
  const replay = await (await replayResponse).json();
  expect(commands).toHaveLength(2);
  expect(commands[0]).toEqual(commands[1]);
  expect(replay.id).toBe(committed!.id);
  expect(replay.clientId).toBe(committed!.clientId);
  expect(replay.invitationLink).toBeUndefined();
  expect(replay.token).toBeUndefined();
  await expect(
    owner.getByRole("heading", { name: "Ссылка не получена" }),
  ).toBeVisible();
  await expect(
    owner.getByRole("heading", { name: "Приглашение создано" }),
  ).toHaveCount(0);
  await expect(
    owner.getByRole("button", { name: "Копировать ссылку" }),
  ).toHaveCount(0);
  await owner.getByRole("button", { name: "Открыть приглашение" }).click();
  const oldLaunch = new URL(committed!.invitationLink).searchParams.get(
    "startapp",
  )!;
  owner.once("dialog", (dialog) => dialog.accept());
  const reissueResponse = owner.waitForResponse(
    (response) => response.url().endsWith("/reissue") && response.ok(),
  );
  await owner.getByRole("button", { name: "Выпустить новую ссылку" }).click();
  const reissued = await (await reissueResponse).json();
  expect(reissued.id).not.toBe(committed!.id);
  expect(reissued.clientId).toBe(committed!.clientId);
  await expect(
    owner.getByRole("heading", { name: "Приглашение создано" }),
  ).toBeVisible();
  const newLink = new URL(await owner.locator(".ob-link").innerText());
  expect(newLink.origin).toBe("https://t.me");
  expect(newLink.pathname).toBe("/synthetic_bot");
  const newLaunch = newLink.searchParams.get("startapp")!;
  expect(newLaunch).toMatch(/^ci_[A-Za-z0-9_-]{43}$/);
  expect(newLaunch).not.toBe(oldLaunch);
  const stale = await request.post(
    "/api/client/invitations/" + committed!.id + "/claim",
    {
      headers: {
        "X-Telegram-Init-Data": recipientCredential,
        "Idempotency-Key": "synthetic-old-claim-" + committed!.id,
      },
      data: { token: oldLaunch.slice(3), expectedVersion: "1" },
    },
  );
  expect(stale.status()).toBe(404);
  const oldStatus = await request.get(
    "/api/platform/onboarding/invitations/" + committed!.id,
    {
      headers: { "X-Telegram-Init-Data": ownerCredential },
    },
  );
  expect((await oldStatus.json()).status).toBe("revoked");
  const client = await request.get(
    "/api/platform/onboarding/clients/" + committed!.clientId,
    {
      headers: { "X-Telegram-Init-Data": ownerCredential },
    },
  );
  expect(
    (await client.json()).invitations
      .map((item: { id: string }) => item.id)
      .sort(),
  ).toEqual([committed!.id, reissued.id].sort());
  await open(
    recipient,
    "/app",
    await identity(request, "recipient", newLaunch),
  );
  await expect(
    recipient.getByRole("heading", { name: "Приглашение клиента" }),
  ).toBeVisible();
  await recipient.getByRole("button", { name: "Принять приглашение" }).click();
  await expect(recipient.getByText("Разрешено всего: 1")).toBeVisible();
  for (const page of [owner, recipient]) {
    const storage = await page.evaluate(() =>
      JSON.stringify({
        local: { ...localStorage },
        session: { ...sessionStorage },
      }),
    );
    expect(storage).not.toContain(oldLaunch);
    expect(storage).not.toContain(newLaunch);
    expect(storage).not.toContain(ownerCredential);
    expect(storage).not.toContain(recipientCredential);
    expect(page.url()).not.toContain(newLaunch);
  }
  await owner.close();
  await recipient.close();
});

test("cabinet layout creates and renames halls, uploads a plan, saves tables and clears revoked access", async ({
  browser,
  request,
  baseURL,
}) => {
  test.setTimeout(90000);
  const ownerCredential = await identity(request, "owner");
  const credential = await identity(request, "recipient");
  const foreignCredential = await identity(request, "foreign");
  const command = async (path: string, data: unknown, who = credential) => {
    const response = await request.post(path, {
      headers: {
        "X-Telegram-Init-Data": who,
        "Idempotency-Key": "layout-browser-" + crypto.randomUUID(),
      },
      data,
    });
    expect(response.ok(), await response.text()).toBe(true);
    return response.json();
  };
  const invitation = await command(
    "/api/platform/onboarding/invitations",
    {
      clientName: "Layout browser " + Date.now(),
      recipientLabel: "Synthetic layout owner",
    },
    ownerCredential,
  );
  const token = new URL(invitation.invitationLink).searchParams
    .get("startapp")!
    .slice(3);
  await command("/api/client/invitations/" + invitation.id + "/claim", {
    token,
    expectedVersion: invitation.version,
  });
  const profile = await command(
    "/api/client/clients/" + invitation.clientId + "/clubs",
    {
      venueName: "Layout acceptance club",
      countryCode: "RU",
      cityId: "524901",
      address: "Synthetic layout street " + Date.now(),
    },
  );
  const page = await browser.newPage({
    baseURL,
    viewport: { width: 390, height: 844 },
  });
  const paths: string[] = [];
  page.on("request", (req) => {
    if (new URL(req.url()).pathname.startsWith("/api/"))
      paths.push(new URL(req.url()).pathname);
  });
  await page.goto("/app");
  await open(page, "/app?mode=cabinet", credential);
  const availableContext = await (
    await request.get("/api/client/context", {
      headers: { "X-Telegram-Init-Data": credential },
    })
  ).json();
  if (availableContext.clients.length > 1) {
    const organization = page.getByRole("button").filter({
      has: page.getByText(invitation.clientName, { exact: true }),
    });
    await expect(organization).toBeVisible();
    await organization.click();
  }
  await page.getByRole("button", { name: /Layout acceptance club/ }).click();
  await page.getByRole("button", { name: /Залы и столы/ }).click();
  await expect(page.getByLabel("JSON схемы")).toHaveCount(0);
  await page.getByLabel("Название", { exact: true }).fill("Основной зал");
  await page.getByRole("button", { name: "Создать", exact: true }).click();
  await expect(page.getByText("Основной зал", { exact: true })).toBeVisible();
  await page
    .getByRole("button", { name: "Редактировать", exact: true })
    .click();
  await page.getByLabel("Название", { exact: true }).fill("Первый зал");
  await page.getByRole("button", { name: "Сохранить", exact: true }).click();
  await expect(page.getByText("Первый зал", { exact: true })).toBeVisible();
  await page
    .getByRole("button", { name: "Открыть схему", exact: true })
    .click();
  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
    "base64",
  );
  await page
    .locator("input[type=file]")
    .setInputFiles({ name: "plan.png", mimeType: "image/png", buffer: png });
  const image = page.getByTestId("hall-plan-image");
  await expect(image).toBeVisible();
  await expect
    .poll(() => image.evaluate((img) => (img as HTMLImageElement).naturalWidth))
    .toBeGreaterThan(0);
  const stage = page.getByTestId("hall-plan-stage");
  await stage.click({ position: { x: 150, y: 90 } });
  await expect(page.getByLabel("Номер", { exact: true })).toHaveValue("1");
  await page.getByLabel("Название", { exact: true }).fill("У окна");
  await page.getByLabel("Вместимость").fill("6");
  await page.getByRole("button", { name: "Сохранить", exact: true }).click();
  await expect(page.getByRole("button", { name: "#1 · У окна" })).toBeVisible();
  const layoutPath = "/api/client/clubs/" + profile.id + "/layout";
  const originalLayout = await (
    await request.get(layoutPath, {
      headers: { "X-Telegram-Init-Data": credential },
    })
  ).json();
  const marker = page.locator('[data-testid^="table-marker-"]');
  const markerBox = await marker.boundingBox();
  const movedResponse = page.waitForResponse(
    (response) =>
      response.request().method() === "PATCH" &&
      /\/tables\/[1-9][0-9]*$/.test(new URL(response.url()).pathname) &&
      response.ok(),
  );
  await page.mouse.move(
    markerBox!.x + markerBox!.width / 2,
    markerBox!.y + markerBox!.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    markerBox!.x + markerBox!.width / 2 + 15,
    markerBox!.y + markerBox!.height / 2 + 10,
    { steps: 4 },
  );
  await page.mouse.up();
  const moved = await (await movedResponse).json();
  expect(moved.halls[0].tables[0].x).not.toBe(
    originalLayout.halls[0].tables[0].x,
  );
  const before = await (
    await request.get(layoutPath, {
      headers: { "X-Telegram-Init-Data": credential },
    })
  ).json();
  expect(before.halls).toHaveLength(1);
  expect(before.halls[0].tables[0]).toMatchObject({
    tableNumber: 1,
    label: "У окна",
    capacity: 6,
  });
  expect(before.halls[0].tables[0].x).toBe(moved.halls[0].tables[0].x);
  expect(before.halls[0].tables[0].x).toBeGreaterThanOrEqual(0);
  expect(before.halls[0].tables[0].x).toBeLessThanOrEqual(1);
  const nativeBack = () =>
    page.evaluate(() => {
      const telegram = window as unknown as {
        Telegram: { WebView: { receiveEvent: (event: string) => void } };
      };
      telegram.Telegram.WebView.receiveEvent("back_button_pressed");
    });
  await page.getByLabel("Название", { exact: true }).fill("Несохранённое");
  page.once("dialog", (dialog) => dialog.dismiss());
  await nativeBack();
  await expect(page.getByLabel("Название", { exact: true })).toHaveValue(
    "Несохранённое",
  );
  await page.getByLabel("Название", { exact: true }).fill("У окна");
  await nativeBack();
  await expect(
    page.getByRole("button", { name: "Открыть схему" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Назад", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Layout acceptance club", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: /Залы и столы/ }).click();
  await page.getByRole("button", { name: "Открыть схему" }).click();
  await page.getByRole("button", { name: "#1 · У окна" }).click();
  await expect(page.getByLabel("Вместимость")).toHaveValue("6");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
  expect(paths.some((path) => path.startsWith("/api/admin/"))).toBe(false);
  const foreign = await request.get(layoutPath, {
    headers: { "X-Telegram-Init-Data": foreignCredential },
  });
  expect(foreign.status()).toBe(404);
  const clientResponse = await request.get(
    "/api/platform/onboarding/clients/" + invitation.clientId,
    { headers: { "X-Telegram-Init-Data": ownerCredential } },
  );
  const detail = await clientResponse.json();
  const member = detail.members.find(
    (item: { active: boolean }) => item.active,
  );
  await command(
    "/api/platform/onboarding/clients/" +
      invitation.clientId +
      "/members/" +
      member.userId +
      "/revoke",
    { expectedVersion: member.version },
    ownerCredential,
  );
  await page.evaluate(() => window.dispatchEvent(new Event("pageshow")));
  await expect(page.getByText(/Доступ утрачен/)).toBeVisible();
  await expect(image).toHaveCount(0);
  await expect(page.getByRole("button", { name: "#1 · У окна" })).toHaveCount(
    0,
  );
  await page.close();
});
