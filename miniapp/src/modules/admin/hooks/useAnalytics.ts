import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminApiError } from '../api/admin.api';
import { AnalyticsResponse, getAnalytics } from '../api/adminAnalytics.api';
import { isRequestCanceled } from '../../../shared/api/error';

type AnalyticsStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

interface AnalyticsState {
  status: AnalyticsStatus;
  data: AnalyticsResponse | null;
  errorMessage: string;
  canRetry: boolean;
}

export function useAnalytics(clubId?: number, nightStartUtc?: string, windowDays?: number, onForbidden?: () => void) {
  const [state, setState] = useState<AnalyticsState>({
    status: 'idle',
    data: null,
    errorMessage: '',
    canRetry: false,
  });
  const controllerRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  const load = useCallback(async () => {
    if (!clubId || !nightStartUtc) {
      controllerRef.current?.abort();
      setState({ status: 'idle', data: null, errorMessage: '', canRetry: false });
      return;
    }
    const requestId = ++requestIdRef.current;
    controllerRef.current?.abort();
    controllerRef.current = new AbortController();
    setState((prev) => ({ ...prev, status: 'loading', errorMessage: '', canRetry: false }));
    try {
      const details = await getAnalytics(clubId, nightStartUtc, windowDays, controllerRef.current.signal);
      if (requestId !== requestIdRef.current) return;
      setState({ status: 'ready', data: details, errorMessage: '', canRetry: false });
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestId !== requestIdRef.current) return;
      if (error instanceof AdminApiError) {
        const status = error.status;
        if (status === 401) {
          setState({ status: 'unauthorized', data: null, errorMessage: 'Нужна авторизация', canRetry: false });
          return;
        }
        if (status === 403) {
          onForbidden?.();
          setState({ status: 'forbidden', data: null, errorMessage: 'Нет доступа', canRetry: false });
          return;
        }
        if (!status) {
          setState({ status: 'error', data: null, errorMessage: 'Не удалось связаться с сервером', canRetry: true });
          return;
        }
        if (status >= 500) {
          setState({ status: 'error', data: null, errorMessage: 'Сервис временно недоступен', canRetry: true });
          return;
        }
        setState({ status: 'error', data: null, errorMessage: error.message, canRetry: false });
        return;
      }
      setState({ status: 'error', data: null, errorMessage: 'Не удалось загрузить аналитику', canRetry: false });
    }
  }, [clubId, nightStartUtc, onForbidden, windowDays]);

  useEffect(() => {
    void load();
    return () => controllerRef.current?.abort();
  }, [load]);

  return { ...state, reload: load };
}
