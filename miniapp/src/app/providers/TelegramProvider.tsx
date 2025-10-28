import { createContext, useContext, useEffect, useState } from 'react';
import WebApp from '@twa-dev/sdk';

interface TelegramContextValue {
  webApp: typeof WebApp;
}

const TelegramContext = createContext<TelegramContextValue | null>(null);

/** Telegram SDK provider initializing WebApp and exposing context. */
export function TelegramProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    WebApp.ready();
    WebApp.expand();
    setReady(true);
  }, []);

  if (!ready) return null;
  return <TelegramContext.Provider value={{ webApp: WebApp }}>{children}</TelegramContext.Provider>;
}

/** Hook returning initialized Telegram WebApp instance. */
export function useTelegram() {
  const ctx = useContext(TelegramContext);
  if (!ctx) throw new Error('TelegramProvider missing');
  return ctx.webApp;
}
