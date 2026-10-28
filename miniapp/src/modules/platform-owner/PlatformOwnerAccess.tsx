import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { useInitData } from '../auth/hooks/useInitData';
import { setInitData } from '../../shared/api/http';
import { checkPlatformOwnerAccess } from './platformOwner.api';
import { useInvitationDraft } from '../client/useInvitationDraft';

const Screens = lazy(() => import('./PlatformOwnerScreens'));
type AccessStatus = 'loading' | 'allowed' | 'denied' | 'unauthorized' | 'error';

/** Client state controls presentation only. Server operations need their own authorization. */
export default function PlatformOwnerAccess() {
  const { initData, refreshEpoch = 0 } = useInitData();
  const [attempt, setAttempt] = useState(0);
  const [access, setAccess] = useState<{
    credential: string;
    refreshEpoch: number;
    status: AccessStatus;
  }>({ credential: '', refreshEpoch: -1, status: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    setInitData(initData);
    setAccess({
      credential: initData,
      refreshEpoch,
      status: initData ? 'loading' : 'unauthorized',
    });
    if (initData) {
      checkPlatformOwnerAccess(controller.signal)
        .then(() => {
          if (!controller.signal.aborted)
            setAccess({
              credential: initData,
              refreshEpoch,
              status: 'allowed',
            });
        })
        .catch((error: unknown) => {
          if (controller.signal.aborted) return;
          const status = axios.isAxiosError(error)
            ? error.response?.status
            : undefined;
          setAccess({
            credential: initData,
            refreshEpoch,
            status:
              status === 403
                ? 'denied'
                : status === 401
                  ? 'unauthorized'
                  : 'error',
          });
        });
    }
    return () => controller.abort();
  }, [initData, attempt, refreshEpoch]);

  useEffect(() => {
    const recheck = () => {
      setAccess({ credential: '', refreshEpoch: -1, status: 'loading' });
      setAttempt((value) => value + 1);
    };
    const onVisibility = () => {
      if (document.hidden)
        setAccess({ credential: '', refreshEpoch: -1, status: 'loading' });
      else recheck();
    };
    window.addEventListener('pageshow', recheck);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pageshow', recheck);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  const status =
    access.credential === initData && access.refreshEpoch === refreshEpoch
      ? access.status
      : 'loading';
  // Revalidation hides/unmounts private screens, but must not lose an uncertain
  // command. Temporary hidden-page credential suppression is not account change.
  const lastCredential = useRef('');
  if (initData || !document.hidden) lastCredential.current = initData;
  const denied =
    Boolean(initData) &&
    access.credential === initData &&
    (access.status === 'denied' || access.status === 'unauthorized');
  const invite = useInvitationDraft(
    denied ? '' : lastCredential.current,
    status !== 'allowed',
  );
  const loading = (
    <main
      aria-busy="true"
      aria-label="Проверка доступа к платформе"
      className="min-h-screen bg-gray-50 p-6"
    >
      Проверяем доступ…
    </main>
  );
  if (status === 'loading') return loading;
  if (status === 'allowed')
    return (
      <Suspense fallback={loading}>
        <Screens
          key={refreshEpoch + ':' + initData}
          credential={initData}
          invite={invite}
        />
      </Suspense>
    );
  return (
    <main
      lang="ru"
      className="min-h-screen bg-gray-50 p-6 flex items-center justify-center"
    >
      <div className="max-w-sm space-y-3">
        <h1 className="text-xl font-semibold">
          {status === 'denied'
            ? 'Нет доступа к управлению платформой'
            : status === 'unauthorized'
              ? 'Нужна авторизация в Telegram'
              : 'Не удалось проверить доступ'}
        </h1>
        <p>
          {status === 'denied'
            ? 'Кабинет доступен только владельцу платформы.'
            : status === 'unauthorized'
              ? 'Откройте мини-приложение заново из Telegram.'
              : 'Повторите проверку, когда соединение восстановится.'}
        </p>
        <button
          type="button"
          className="min-h-11 text-blue-700"
          onClick={() => setAttempt((value) => value + 1)}
        >
          Повторить проверку
        </button>
      </div>
    </main>
  );
}
