import { expect, test, type Route } from "@playwright/test";
import type { CalendarView } from "../src/modules/client/calendar";
const synthetic = new URLSearchParams({
  user: JSON.stringify({ id: 880062011, first_name: "Synthetic" }),
  auth_date: "1",
  hash: "synthetic",
}).toString();
const entry = (screen: string, id = "42") =>
  "/app?mode=client&screen=" +
  screen +
  "&resource=" +
  id +
  "#tgWebAppData=" +
  encodeURIComponent(synthetic) +
  "&tgWebAppVersion=7.10&tgWebAppPlatform=ios";
const profile = {
  id: "42",
  clientId: "21",
  clientName: "Private tenant",
  name: "Private review club",
  description: "Private profile",
  timezone: "Europe/Moscow",
  countryCode: "RU",
  city: "Москва",
  address: "Private address",
  unit: "",
  version: "1",
  savedAt: "2026-11-09T00:00:00Z",
  permissions: ["client.club.read", "client.club.profile.write"],
  availableActions: ["update"],
};
const prep = {
  clubId: "42",
  clientId: "21",
  name: profile.name,
  version: "2",
  status: "in_progress",
  published: false,
  reason: null,
  assignee: {
    userId: "9",
    displayName: "Responsible",
    username: null,
    owner: false,
    active: true,
  },
  invitation: null,
  canManage: false,
  canSubmit: true,
  canInvite: false,
  canWrite: true,
  configurationChanged: false,
};
const calendar: CalendarView = {
  clubId: "42",
  name: profile.name,
  timezone: profile.timezone,
  version: "1",
  canWrite: true,
  weekly: [],
  holidays: [],
  exceptions: [],
  invalidDates: [],
  nights: [
    {
      businessDate: "2090-01-08",
      opensAt: "2090-01-09T00:00:00+03:00",
      closesAt: "2090-01-09T08:00:00+03:00",
      source: "EXCEPTION",
      nightId: "99",
    },
  ],
};
for (const ignoreAbort of [false, true]) {
  test(`P2 club revocation rejects all late responses and allows only fresh recovery (ignore abort ${ignoreAbort})`, async ({
    page,
  }) => {
    if (ignoreAbort)
      await page.addInitScript(() => {
        const NativeAbortController = window.AbortController;
        window.AbortController = class extends NativeAbortController {
          abort() {}
        };
      });
    let holdProfile = false;
    let holdPrep = false;
    let restored = false;
    const late: { route: Route; data: unknown }[] = [];
    let mutation!: Route;
    await page.route("**/api/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith("/profile")) {
        if (holdProfile) {
          late.push({ route, data: profile });
          return;
        }
        return route.fulfill({
          json: {
            ...profile,
            name: restored ? "Restored club" : profile.name,
          },
        });
      }
      if (path.endsWith("/preparation")) {
        if (route.request().method() === "PATCH") {
          mutation = route;
          return;
        }
        if (holdPrep) {
          late.push({ route, data: prep });
          return;
        }
        return route.fulfill({ json: prep });
      }
      if (path.endsWith("/calendar")) {
        if (!restored) {
          late.push({ route, data: calendar });
          return;
        }
        return route.fulfill({ json: { ...calendar, nights: [] } });
      }
      return route.fulfill({ status: 404, json: { code: "not_found" } });
    });
    await page.goto(entry("club"));
    await expect.poll(() => late.length).toBe(1);
    await page
      .getByRole("button", { name: "Передать на проверку", exact: true })
      .click();
    await expect.poll(() => Boolean(mutation)).toBe(true);
    holdProfile = true;
    holdPrep = true;
    await page.evaluate(() => window.dispatchEvent(new Event("focus")));
    await expect
      .poll(() =>
        late.some(({ route }) => route.request().url().endsWith("/profile")),
      )
      .toBe(true);
    await mutation.fulfill({ status: 404, json: { code: "not_found" } });
    await expect(
      page.getByText(/Доступ утрачен или запись недоступна/).first(),
    ).toBeVisible();
    const staleDenial = late.find(({ route }) =>
      route.request().url().endsWith("/preparation"),
    );
    for (const item of late)
      if (item !== staleDenial) await item.route.fulfill({ json: item.data });
    await expect(
      page.getByText("Вечеринка 08.01.2090", { exact: true }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("heading", { name: profile.name, exact: true }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("button", { name: "Перейти к настройкам", exact: true }),
    ).toHaveCount(0);
    await page.screenshot({
      path: test.info().outputPath("club-denied.png"),
      fullPage: true,
    });
    holdProfile = false;
    holdPrep = false;
    restored = true;
    await page
      .getByRole("button", { name: "Повторить загрузку", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Restored club", exact: true }),
    ).toBeVisible();
    if (staleDenial) {
      const delivered = ignoreAbort
        ? page.waitForResponse(
            (response) =>
              response.url().endsWith("/preparation") &&
              response.status() === 403,
          )
        : undefined;
      await staleDenial.route.fulfill({
        status: 403,
        json: { code: "forbidden" },
      });
      if (delivered) await (await delivered).finished();
      await page.waitForLoadState("networkidle");
    }
    await expect(
      page.getByRole("heading", { name: "Restored club", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "Нужна авторизация в Telegram" }),
    ).toHaveCount(0);
    await expect(
      page.getByText("Вечеринка 08.01.2090", { exact: true }),
    ).toHaveCount(0);
  });
}
test.describe("P2 DST lookback in the club timezone", () => {
  test.use({ timezoneId: "Pacific/Honolulu" });
  for (const [today, kind] of [
    ["2027-03-28", "gap"],
    ["2027-10-31", "overlap"],
  ] as const) {
    test(`${kind} requires a calendar decision for the previous business date`, async ({
      page,
    }) => {
      await page.clock.setFixedTime(new Date(today + "T00:00:00Z"));
      let writes = 0;
      const view: CalendarView = {
        ...calendar,
        timezone: "Europe/London",
        reconciliation: { businessDate: today, storedDates: [] },
        nights: [],
        weekly: [
          {
            day: 6,
            mode: "open",
            open: "01:30",
            close: "05:00",
            startNextDay: true,
            nextDay: false,
          },
        ],
      };
      await page.route("**/api/**", async (route) => {
        if (new URL(route.request().url()).pathname.endsWith("/calendar")) {
          if (route.request().method() === "PATCH") writes++;
          return route.fulfill({ json: view });
        }
        return route.fulfill({
          json: { ...profile, timezone: view.timezone },
        });
      });
      await page.goto(entry("calendar"));
      expect(
        await page.evaluate(
          () => Intl.DateTimeFormat().resolvedOptions().timeZone,
        ),
      ).toBe("Pacific/Honolulu");
      await page
        .getByRole("button", {
          name: "Сформировать ближайшие ночи",
          exact: true,
        })
        .click();
      const confirm = page.getByRole("button", {
        name: "Подтвердить формирование",
        exact: true,
      });
      await expect(confirm).toBeDisabled();
      expect(writes).toBe(0);
      if (kind === "gap") {
        await expect(
          page.getByText(/Такого местного времени нет/),
        ).toBeVisible();
        await expect(page.getByText(/28.03.2027 01:30/)).toBeVisible();
        await page
          .getByText(/Такого местного времени нет/)
          .scrollIntoViewIfNeeded();
      } else {
        await expect(page.getByText(/Время встречается дважды/)).toBeVisible();
        const choice = page.getByLabel("Выбор времени при переводе часов");
        await choice.scrollIntoViewIfNeeded();
        await page.screenshot({
          path: test.info().outputPath("dst-overlap-choice-required.png"),
          fullPage: true,
        });
        await choice.selectOption("first");
        await expect(confirm).toBeEnabled();
        await page.screenshot({
          path: test.info().outputPath("dst-overlap-first-confirmation.png"),
          fullPage: true,
        });
        await confirm.click();
        await expect.poll(() => writes).toBe(1);
        await expect(
          page.getByText("Сохранено", { exact: true }),
        ).toBeVisible();
      }
      await page.screenshot({
        path: test.info().outputPath("dst-" + kind + ".png"),
        fullPage: true,
      });
    });
  }
});

test("P2 transient failure retains a valid cabinet and retries the same unknown command", async ({
  page,
}) => {
  let temporarilyUnavailable = false;
  let current = prep;
  const attempts: { body: string | null; key: string | undefined }[] = [];
  const pending: Route[] = [];
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/profile"))
      return temporarilyUnavailable
        ? route.fulfill({ status: 503, json: { code: "unavailable" } })
        : route.fulfill({ json: profile });
    if (path.endsWith("/calendar")) {
      pending.push(route);
      return;
    }
    if (path.endsWith("/preparation")) {
      if (route.request().method() !== "PATCH")
        return route.fulfill({ json: current });
      attempts.push({
        body: route.request().postData(),
        key: route.request().headers()["idempotency-key"],
      });
      if (attempts.length === 1)
        return route.fulfill({ status: 503, json: { code: "unavailable" } });
      current = {
        ...prep,
        version: "3",
        status: "submitted",
        canSubmit: false,
        canWrite: false,
      };
      return route.fulfill({ json: current });
    }
    return route.fulfill({ status: 404, json: { code: "not_found" } });
  });
  await page.goto(entry("club"));
  await page
    .getByRole("button", { name: "Передать на проверку", exact: true })
    .click();
  await expect(
    page.getByText("Результат операции пока неизвестен", { exact: true }),
  ).toBeVisible();
  temporarilyUnavailable = true;
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect(
    page.getByText("Не удалось загрузить данные. Повторите попытку.", {
      exact: true,
    }),
  ).toBeVisible();
  for (const route of pending) await route.fulfill({ json: calendar });
  await expect(
    page.getByRole("heading", { name: profile.name, exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Вечеринка 08.01.2090", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Повторить ту же операцию", exact: true })
    .click();
  await expect(
    page.getByText("Отправлено на проверку", { exact: true }),
  ).toBeVisible();
  expect(attempts).toHaveLength(2);
  expect(attempts[1]).toEqual(attempts[0]);
});

test("P2 switching to another allowed club survives late old 403 and calendar 200", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const NativeAbortController = window.AbortController;
    window.AbortController = class extends NativeAbortController {
      abort() {}
    };
  });
  let profileReads = 0;
  let mutation!: Route;
  const oldReads: { route: Route; status: number; data: unknown }[] = [];
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const other = path.includes("/43/");
    if (path.endsWith("/profile")) {
      if (!other && ++profileReads > 1) {
        oldReads.push({ route, status: 403, data: { code: "forbidden" } });
        return;
      }
      return route.fulfill({
        json: other
          ? {
              ...profile,
              id: "43",
              clientId: "22",
              name: "Other allowed club",
            }
          : profile,
      });
    }
    if (path.endsWith("/calendar")) {
      if (!other) {
        oldReads.push({ route, status: 200, data: calendar });
        return;
      }
      return route.fulfill({
        json: { ...calendar, clubId: "43", nights: [] },
      });
    }
    if (path.endsWith("/preparation")) {
      if (route.request().method() === "PATCH") {
        mutation = route;
        return;
      }
      return route.fulfill({
        json: other
          ? {
              ...prep,
              clubId: "43",
              clientId: "22",
              canSubmit: false,
              canWrite: false,
            }
          : prep,
      });
    }
    return route.fulfill({ status: 404, json: { code: "not_found" } });
  });
  await page.goto(entry("club"));
  await page
    .getByRole("button", { name: "Передать на проверку", exact: true })
    .click();
  await expect.poll(() => Boolean(mutation)).toBe(true);
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await expect
    .poll(() => oldReads.some(({ status }) => status === 403))
    .toBe(true);
  await mutation.fulfill({ status: 404, json: { code: "not_found" } });
  await expect(
    page.getByRole("button", { name: "Мои заведения", exact: true }),
  ).toBeVisible();
  await page.evaluate(() => {
    history.pushState(
      { onboarding: true, clientPosition: 1 },
      "",
      "/app?mode=client&screen=club&resource=43",
    );
    window.dispatchEvent(
      new PopStateEvent("popstate", { state: history.state }),
    );
  });
  await expect(
    page.getByRole("heading", { name: "Other allowed club", exact: true }),
  ).toBeVisible();
  for (const item of oldReads)
    await item.route.fulfill({ status: item.status, json: item.data });
  await page.waitForLoadState("networkidle");
  await expect(
    page.getByRole("heading", { name: "Other allowed club", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: profile.name, exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText("Вечеринка 08.01.2090", { exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: test.info().outputPath("other-club-after-stale403.png"),
    fullPage: true,
  });
});
