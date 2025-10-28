import { useEffect, useState } from 'react';
import { useTelegram } from './TelegramProvider';

/** Applies Telegram theme params to Tailwind via CSS variables. */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const webApp = useTelegram();
  const [scheme, setScheme] = useState(webApp.colorScheme);

  useEffect(() => {
    const listener = (e: unknown) => {
      setScheme(webApp.colorScheme);
    };
    webApp.onEvent('themeChanged', listener);
    return () => webApp.offEvent('themeChanged', listener as any);
  }, [webApp]);

  return <div data-theme={scheme}>{children}</div>;
}
