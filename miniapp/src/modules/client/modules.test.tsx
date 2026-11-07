import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import ClubModules from "./ClubModules";
import { ModuleSettingsView, moduleIds, validModules } from "./modules.types";

vi.mock("../../app/providers/TelegramProvider", () => ({
  useTelegram: () => ({}),
}));

const initial: ModuleSettingsView = {
  clubId: "42",
  clientId: "7",
  name: "Клуб",
  version: "1",
  canWrite: true,
  settingsPresent: false,
  earlyWindowMinutes: null,
  flags: {
    stampsEnabled: false,
    earlyEnabled: false,
    badgesEnabled: false,
    prizesEnabled: false,
    contestsEnabled: false,
    tablesLoyaltyEnabled: false,
  },
  modules: moduleIds.map((id) => ({
    id,
    status: id === "early_arrival" ? "requires_setup" : "unavailable",
    blockedBy: "DEC-013",
    savedEnabled: false,
    activationAvailable: false,
  })),
};
const reply = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status });
function mount() {
  vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
  return render(
    <ClubModules
      credential="synthetic"
      id="42"
      back={vi.fn()}
      reportDirty={vi.fn()}
      reportPending={vi.fn()}
    />,
  );
}
const input = () =>
  screen.getByRole<HTMLInputElement>("textbox", {
    name: /Ранний приход · минут/,
  });
const save = () =>
  fireEvent.click(screen.getByRole("button", { name: "Сохранить правило" }));
const change = (value: string) =>
  fireEvent.change(input(), { target: { value } });
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("reads actual values without mutations and saves only the early rule then reopens persisted state", async () => {
  let stored = { ...initial, settingsPresent: true, earlyWindowMinutes: 90 };
  const writes: Record<string, unknown>[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_, options) => {
    if (options?.method !== "PATCH") return reply(stored);
    const payload = JSON.parse(String(options.body));
    writes.push(payload);
    stored = {
      ...stored,
      earlyWindowMinutes: payload.earlyWindowMinutes,
      version: String(Number(stored.version) + 1),
    };
    return reply(stored);
  });
  const mounted = mount();
  await screen.findByRole("textbox", { name: /Ранний приход · минут/ });
  expect(input().value).toBe("90");
  expect(writes).toHaveLength(0);
  expect(screen.queryByRole("checkbox")).toBeNull();
  expect(screen.getByText(/Нет настройки включения музыки/)).toBeTruthy();
  change("180");
  save();
  await screen.findByText("Сохранено");
  expect(writes).toEqual([{ expectedVersion: "1", earlyWindowMinutes: 180 }]);
  mounted.unmount();
  mount();
  await screen.findByRole("textbox", { name: /Ранний приход · минут/ });
  expect(input().value).toBe("180");
  change("");
  save();
  await waitFor(() => expect(writes).toHaveLength(2));
  expect(writes[1]).toEqual({ expectedVersion: "2", earlyWindowMinutes: null });
});

it("keeps exact request key and body when the result is unknown", async () => {
  const writes: { key: string | undefined; body: unknown }[] = [];
  let attempts = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_, options) => {
    if (options?.method !== "PATCH") return reply(initial);
    writes.push({
      key: (options.headers as Record<string, string>)["Idempotency-Key"],
      body: JSON.parse(String(options.body)),
    });
    if (++attempts === 1) throw new TypeError("offline");
    return reply({
      ...initial,
      earlyWindowMinutes: 60,
      version: "2",
      settingsPresent: true,
    });
  });
  mount();
  await screen.findByRole("textbox", { name: /Ранний приход · минут/ });
  change("60");
  save();
  fireEvent.click(
    await screen.findByRole("button", { name: "Повторить ту же операцию" }),
  );
  await screen.findByText("Сохранено");
  expect(writes).toHaveLength(2);
  expect(writes[1]).toEqual(writes[0]);
  expect(writes[0].key).toBeTruthy();
});

it("shows the conflicting stored value and requires explicit rebase before another write", async () => {
  let stored = initial;
  const writes: Record<string, unknown>[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_, options) => {
    if (options?.method !== "PATCH") return reply(stored);
    writes.push(JSON.parse(String(options.body)));
    if (writes.length === 1) {
      stored = { ...initial, version: "2", earlyWindowMinutes: 45 };
      return reply({ code: "version_conflict" }, 409);
    }
    stored = { ...stored, version: "3", earlyWindowMinutes: 90 };
    return reply(stored);
  });
  mount();
  await screen.findByRole("textbox", { name: /Ранний приход · минут/ });
  change("90");
  save();
  await screen.findByText(/Сохранено: 45 мин/);
  expect(input().value).toBe("90");
  expect(
    screen.getByRole<HTMLButtonElement>("button", { name: "Сохранить правило" })
      .disabled,
  ).toBe(true);
  fireEvent.click(
    screen.getByRole("button", {
      name: "Оставить моё значение для нового сохранения",
    }),
  );
  save();
  await screen.findByText("Сохранено");
  expect(writes).toEqual([
    { expectedVersion: "1", earlyWindowMinutes: 90 },
    { expectedVersion: "2", earlyWindowMinutes: 90 },
  ]);
});

it("rejects malformed numeric input and enforces read-only permission in the screen", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(reply(initial));
  const mounted = mount();
  await screen.findByRole("textbox", { name: /Ранний приход · минут/ });
  change("-1");
  expect(
    screen.getByRole<HTMLButtonElement>("button", { name: "Сохранить правило" })
      .disabled,
  ).toBe(true);
  expect(screen.getByText(/Введите целое неотрицательное/)).toBeTruthy();
  mounted.unmount();
  fetch.mockImplementation(async () => reply({ ...initial, canWrite: false }));
  mount();
  await screen.findByText("Доступно только чтение настроек.");
  expect(screen.queryByRole("textbox")).toBeNull();
  expect(
    screen.queryByRole("button", { name: "Сохранить правило" }),
  ).toBeNull();
});

it("conceals module data when a read is revoked and rejects an earlier successful mutation", async () => {
  let resolveWrite: (response: Response) => void = () => undefined;
  let revoked = false;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_, options) => {
    if (options?.method === "PATCH")
      return new Promise<Response>((resolve) => {
        resolveWrite = resolve;
      });
    return revoked ? reply({ code: "not_found" }, 404) : reply(initial);
  });
  mount();
  await screen.findByRole("textbox", { name: /Ранний приход · минут/ });
  change("120");
  save();
  revoked = true;
  await act(async () => {
    window.dispatchEvent(new Event("focus"));
  });
  await screen.findByText(/Доступ утрачен/);
  expect(screen.queryByRole("textbox")).toBeNull();
  await act(async () => {
    resolveWrite(reply({ ...initial, version: "2", earlyWindowMinutes: 120 }));
  });
  expect(screen.queryByText("Сохранено")).toBeNull();
  expect(screen.queryByRole("textbox")).toBeNull();
});

it("rejects wrong club snapshots and malformed module availability instead of presenting foreign data", async () => {
  expect(validModules({ ...initial, modules: [] })).toBe(false);
  expect(validModules({ ...initial, earlyWindowMinutes: -1 })).toBe(false);
  vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
    reply({ ...initial, clubId: "99", name: "Foreign club" }),
  );
  mount();
  await screen.findByText(/Доступ утрачен/);
  expect(screen.queryByText("Foreign club")).toBeNull();
  expect(screen.queryByRole("textbox")).toBeNull();
});

it("keeps an unknown command retry available after write permission is revoked without automatically writing", async () => {
  let readonly = false;
  const writes: { key: string | undefined; body: unknown }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_, options) => {
    if (options?.method !== "PATCH")
      return reply({ ...initial, canWrite: !readonly });
    writes.push({
      key: (options.headers as Record<string, string>)["Idempotency-Key"],
      body: JSON.parse(String(options.body)),
    });
    if (writes.length === 1) throw new TypeError("offline");
    return reply({ code: "not_found" }, 404);
  });
  mount();
  await screen.findByRole("textbox", { name: /Ранний приход · минут/ });
  change("120");
  save();
  await screen.findByRole("button", { name: "Повторить ту же операцию" });
  readonly = true;
  await act(async () => {
    window.dispatchEvent(new Event("focus"));
  });
  await screen.findByText("Доступно только чтение настроек.");
  expect(screen.queryByRole("textbox")).toBeNull();
  expect(writes).toHaveLength(1);
  fireEvent.click(
    screen.getByRole("button", { name: "Повторить ту же операцию" }),
  );
  await screen.findByText(/Доступ утрачен/);
  expect(writes).toHaveLength(2);
  expect(writes[1]).toEqual(writes[0]);
  expect(screen.queryByText("Сохранено")).toBeNull();
  expect(
    screen.queryByRole("button", { name: "Повторить ту же операцию" }),
  ).toBeNull();
});
