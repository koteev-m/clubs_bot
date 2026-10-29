import { useEffect, useRef, useState } from "react";
import { useInitData } from "../modules/auth/hooks/useInitData";
import {
  onboardingRequest,
  OnboardingError,
} from "../modules/client/onboarding.api";
import ClientAccess from "../modules/client/ClientAccess";
import PlatformOwnerAccess from "../modules/platform-owner/PlatformOwnerAccess";
import SupportShell from "../modules/support/pages/SupportShell";
import { setInitData } from "../shared/api/http";

type Destination = "owner" | "client" | "support" | "guest";

/** Each response is signed-identity scoped; URL modes and claimed roles are never consulted. */
export async function resolveCabinet(
  credential: string,
  signal: AbortSignal,
  previousDestination?: Destination,
): Promise<Destination> {
  try {
    const owner = await onboardingRequest<{ platformOwner: boolean }>(
      credential,
      "/api/platform/access",
      { signal },
    );
    if (owner.platformOwner !== true)
      throw new Error("Invalid access response");
    return "owner";
  } catch (error) {
    if (!(error instanceof OnboardingError) || error.status !== 403)
      throw error;
  }
  const context = await onboardingRequest<{ clients: unknown[] }>(
    credential,
    "/api/client/context",
    { signal },
  );
  if (!Array.isArray(context.clients))
    throw new Error("Invalid client context");
  if (context.clients.length) return "client";
  if (previousDestination === "client")
    throw new OnboardingError(403, "access_lost");
  try {
    const clubs = await onboardingRequest<unknown[]>(
      credential,
      "/api/support/staff/clubs",
      { signal },
    );
    if (!Array.isArray(clubs)) throw new Error("Invalid staff context");
    return clubs.length ? "support" : "guest";
  } catch (error) {
    if (error instanceof OnboardingError && error.status === 403)
      return "guest";
    throw error;
  }
}

export default function CabinetEntry() {
  const { initData, refreshEpoch = 0 } = useInitData();
  const lastCredential = useRef("");
  const lastAuthorized = useRef<{
    credential: string;
    destination: Destination;
  }>();
  if (initData || !document.hidden) lastCredential.current = initData;
  const [attempt, setAttempt] = useState(0);
  const [mounted, setMounted] = useState<{
    credential: string;
    destination: Destination;
  }>();
  const [access, setAccess] = useState({
    credential: "",
    epoch: -1,
    status: "loading",
  });
  useEffect(() => {
    const controller = new AbortController();
    setAccess({
      credential: initData,
      epoch: refreshEpoch,
      status: initData ? "loading" : "unauthorized",
    });
    if (initData) {
      resolveCabinet(
        initData,
        controller.signal,
        lastAuthorized.current?.credential === initData
          ? lastAuthorized.current.destination
          : undefined,
      )
        .then((destination) => {
          if (controller.signal.aborted) return;
          setInitData(initData);
          lastAuthorized.current = { credential: initData, destination };
          setMounted({ credential: initData, destination });
          setAccess({
            credential: initData,
            epoch: refreshEpoch,
            status: "allowed",
          });
          if (destination === "guest") {
            const url = new URL(window.location.href);
            url.searchParams.set("mode", "guest");
            window.location.replace(url);
          }
        })
        .catch((error: unknown) => {
          if (controller.signal.aborted) return;
          const lost =
            error instanceof OnboardingError &&
            [401, 403].includes(error.status);
          if (lost) setMounted(undefined);
          setAccess({
            credential: initData,
            epoch: refreshEpoch,
            status:
              error instanceof OnboardingError && error.code === "access_lost"
                ? "revoked"
                : lost
                  ? "unauthorized"
                  : "error",
          });
        });
    }
    return () => controller.abort();
  }, [initData, refreshEpoch, attempt]);
  const status =
    access.credential === initData && access.epoch === refreshEpoch
      ? access.status
      : "loading";
  const current =
    mounted?.credential === lastCredential.current ? mounted : undefined;
  return (
    <>
      {current && (
        <div hidden={status !== "allowed"} key={current.credential}>
          {current.destination === "owner" && <PlatformOwnerAccess />}
          {current.destination === "client" && (
            <ClientAccess initialToken={null} />
          )}
          {current.destination === "support" && <SupportShell />}
        </div>
      )}
      {status !== "allowed" && (
        <main
          lang="ru"
          className="min-h-screen bg-gray-50 p-6"
          aria-busy={status === "loading"}
        >
          <h1>
            {status === "loading"
              ? "Проверяем доступ к кабинету…"
              : status === "unauthorized"
                ? "Нужна авторизация в Telegram"
                : status === "revoked"
                  ? "Доступ утрачен"
                  : "Не удалось проверить доступ"}
          </h1>
          {(status === "unauthorized" || status === "revoked") && (
            <p>Откройте мини-приложение заново из Telegram.</p>
          )}
          {status === "error" && (
            <>
              <p>Проверьте соединение и повторите попытку.</p>
              <button onClick={() => setAttempt((value) => value + 1)}>
                Повторить проверку
              </button>
            </>
          )}
        </main>
      )}
    </>
  );
}
