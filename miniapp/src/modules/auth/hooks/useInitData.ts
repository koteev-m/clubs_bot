import { useEffect, useState } from 'react';
import { useTelegram } from '../../../app/providers/TelegramProvider';

export interface InitDataInfo {
  initData: string;
  platform: string;
  version: string;
  refreshEpoch: number;
}

/**
 * Hook extracting initData and other client properties from Telegram WebApp.
 */
export function useInitData(): InitDataInfo {
  const webApp = useTelegram();
  const [info, setInfo] = useState<InitDataInfo>({
    initData: '',
    platform: webApp.platform,
    version: webApp.version,
    refreshEpoch: 0,
  });

  useEffect(() => {
    const refresh = () =>
      setInfo((previous) => ({
        initData: document.hidden ? '' : webApp.initData || '',
        platform: webApp.platform,
        version: webApp.version,
        refreshEpoch: previous.refreshEpoch + 1,
      }));
    refresh();
    window.addEventListener('pageshow', refresh);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      window.removeEventListener('pageshow', refresh);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, [webApp]);

  return info;
}
