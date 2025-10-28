import { useEffect } from 'react';
import { useTelegram } from '../../../app/providers/TelegramProvider';

/**
 * Hook to control Telegram MainButton with a click handler.
 */
export function useTelegramMainButton(text: string, onClick: () => void, enabled: boolean) {
  const webApp = useTelegram();
  useEffect(() => {
    webApp.MainButton.setText(text);
    if (enabled) {
      webApp.MainButton.show();
      webApp.MainButton.onClick(onClick);
    } else {
      webApp.MainButton.hide();
    }
    return () => {
      webApp.MainButton.offClick(onClick);
      webApp.MainButton.hide();
    };
  }, [webApp, text, onClick, enabled]);
}
