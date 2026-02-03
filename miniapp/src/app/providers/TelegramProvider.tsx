import { createContext, useContext, useEffect, useState } from 'react';
import WebApp from '@twa-dev/sdk';

interface TelegramContextValue {
  webApp: typeof WebApp;
}

const TelegramContext = createContext<TelegramContextValue | null>(null);

/** Telegram SDK provider initializing WebApp and exposing context. */
export function TelegramProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);
  const isTestEnv = Boolean((import.meta as { vitest?: unknown }).vitest) || import.meta.env.MODE === 'test';

  useEffect(() => {
    if (isTestEnv) {
      setReady(true);
      return;
    }
    WebApp.ready();
    WebApp.expand();
    setReady(true);
  }, [isTestEnv]);

  if (!ready) return null;
  return <TelegramContext.Provider value={{ webApp: WebApp }}>{children}</TelegramContext.Provider>;
}

/** Hook returning initialized Telegram WebApp instance. */
export function useTelegram() {
  const ctx = useContext(TelegramContext);
  if (!ctx) throw new Error('TelegramProvider missing');
  return ctx.webApp;
}
