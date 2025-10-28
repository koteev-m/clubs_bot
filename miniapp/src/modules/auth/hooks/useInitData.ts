import { useEffect, useState } from 'react';
import { useTelegram } from '../../../app/providers/TelegramProvider';

export interface InitDataInfo {
  initData: string;
  platform: string;
  version: string;
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
  });

  useEffect(() => {
    setInfo({
      initData: webApp.initData || '',
      platform: webApp.platform,
      version: webApp.version,
    });
  }, [webApp]);

  return info;
}
