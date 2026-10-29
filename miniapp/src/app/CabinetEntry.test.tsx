import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import CabinetEntry, { resolveCabinet } from "./CabinetEntry";
import {
  onboardingRequest,
  OnboardingError,
} from "../modules/client/onboarding.api";

const auth = vi.hoisted(() => ({ initData: "signed-A", refreshEpoch: 1 }));
vi.mock("../modules/auth/hooks/useInitData", () => ({
  useInitData: () => auth,
}));
vi.mock("../modules/client/onboarding.api", async (load) => ({
  ...(await load<typeof import("../modules/client/onboarding.api")>()),
  onboardingRequest: vi.fn(),
}));
vi.mock("../shared/api/http", () => ({ setInitData: vi.fn() }));
vi.mock("../modules/client/ClientAccess", () => ({
  default: () => <input aria-label="private draft" defaultValue="" />,
}));
vi.mock("../modules/platform-owner/PlatformOwnerAccess", () => ({
  default: () => <div>protected owner cabinet</div>,
}));
vi.mock("../modules/support/pages/SupportShell", () => ({
  default: () => <div>permitted staff context</div>,
}));
const request = vi.mocked(onboardingRequest);
const forbidden = () => new OnboardingError(403, "forbidden");
const signal = () => new AbortController().signal;

beforeEach(() => {
  vi.clearAllMocks();
  auth.initData = "signed-A";
  auth.refreshEpoch = 1;
  Object.defineProperty(document, "hidden", {
    value: false,
    configurable: true,
  });
});

describe("server-authorized main cabinet entry", () => {
  it("routes an exact owner without a guest or client probe", async () => {
    request.mockResolvedValueOnce({ platformOwner: true });
    expect(await resolveCabinet("signed-owner", signal())).toBe("owner");
    expect(request).toHaveBeenCalledTimes(1);
    expect(request.mock.calls[0].slice(0, 2)).toEqual([
      "signed-owner",
      "/api/platform/access",
    ]);
  });
  it("routes an ordinary client from validated context", async () => {
    request
      .mockRejectedValueOnce(forbidden())
      .mockResolvedValueOnce({ clients: [{ id: "1" }] });
    expect(await resolveCabinet("signed-client", signal())).toBe("client");
    expect(request).toHaveBeenCalledTimes(2);
  });
  it("routes staff only after server returns permitted clubs", async () => {
    request
      .mockRejectedValueOnce(forbidden())
      .mockResolvedValueOnce({ clients: [] })
      .mockResolvedValueOnce([{ id: 4 }]);
    expect(await resolveCabinet("signed-staff", signal())).toBe("support");
    expect(request.mock.calls[2][1]).toBe("/api/support/staff/clubs");
  });
  it("keeps ordinary guest fallback when both management scopes are absent", async () => {
    request
      .mockRejectedValueOnce(forbidden())
      .mockResolvedValueOnce({ clients: [] })
      .mockRejectedValueOnce(forbidden());
    expect(await resolveCabinet("signed-guest", signal())).toBe("guest");
  });
  it.each([401, 500, 0])(
    "does not misclassify access failure %s as guest",
    async (status) => {
      request.mockRejectedValueOnce(new OnboardingError(status, "failed"));
      await expect(
        resolveCabinet("signed-client", signal()),
      ).rejects.toMatchObject({ status });
      expect(request).toHaveBeenCalledTimes(1);
    },
  );
  it("preserves a mounted draft while hidden and revalidates before showing it", async () => {
    request.mockImplementation(async (_credential, path) => {
      if (path === "/api/platform/access") throw forbidden();
      return { clients: [{ id: "1" }] };
    });
    const view = render(<CabinetEntry />);
    const input = await screen.findByLabelText("private draft");
    fireEvent.change(input, { target: { value: "unsaved venue" } });
    Object.defineProperty(document, "hidden", {
      value: true,
      configurable: true,
    });
    auth.initData = "";
    auth.refreshEpoch++;
    view.rerender(<CabinetEntry />);
    expect(input.closest("[hidden]")).not.toBeNull();
    Object.defineProperty(document, "hidden", {
      value: false,
      configurable: true,
    });
    auth.initData = "signed-A";
    auth.refreshEpoch++;
    view.rerender(<CabinetEntry />);
    await waitFor(() => expect(input.closest("[hidden]")).toBeNull());
    expect(
      (screen.getByLabelText("private draft") as HTMLInputElement).value,
    ).toBe("unsaved venue");
  });
  it("clears private state when identity changes and reports a visible auth failure", async () => {
    request.mockImplementation(async (credential, path) => {
      if (credential === "signed-B")
        throw new OnboardingError(401, "unauthorized");
      if (path === "/api/platform/access") throw forbidden();
      return { clients: [{ id: "1" }] };
    });
    const view = render(<CabinetEntry />);
    await screen.findByLabelText("private draft");
    auth.initData = "signed-B";
    auth.refreshEpoch++;
    view.rerender(<CabinetEntry />);
    expect(screen.queryByLabelText("private draft")).toBeNull();
    await screen.findByText("Нужна авторизация в Telegram");
    expect(screen.queryByText("protected owner cabinet")).toBeNull();
  });
  it("clears a revoked client cabinet without querying guest or staff fallback", async () => {
    let active = true;
    request.mockImplementation(async (_credential, path) => {
      if (path === "/api/platform/access") throw forbidden();
      return { clients: active ? [{ id: "1" }] : [] };
    });
    const view = render(<CabinetEntry />);
    const input = await screen.findByLabelText("private draft");
    fireEvent.change(input, { target: { value: "private pending" } });
    active = false;
    request.mockClear();
    auth.refreshEpoch++;
    view.rerender(<CabinetEntry />);
    await screen.findByText("Доступ утрачен");
    expect(screen.queryByLabelText("private draft")).toBeNull();
    expect(request.mock.calls.map((call) => call[1])).toEqual([
      "/api/platform/access",
      "/api/client/context",
    ]);
  });
});
