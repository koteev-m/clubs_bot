import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { getClubGamification, GuestGamificationResponse } from '../api/gamification.api';
import { isRequestCanceled } from '../../../shared/api/error';

type GamificationStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized';

interface GamificationState {
  status: GamificationStatus;
  data: GuestGamificationResponse | null;
  errorMessage: string;
  canRetry: boolean;
}

export function useClubGamification(clubId?: number) {
  const [state, setState] = useState<GamificationState>({
    status: 'idle',
    data: null,
    errorMessage: '',
    canRetry: false,
  });
  const controllerRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  const load = useCallback(async () => {
    if (!clubId) {
      controllerRef.current?.abort();
      setState({ status: 'idle', data: null, errorMessage: '', canRetry: false });
      return;
    }
    const requestId = ++requestIdRef.current;
    controllerRef.current?.abort();
    controllerRef.current = new AbortController();
    setState((prev) => ({ ...prev, status: 'loading', errorMessage: '', canRetry: false }));
    try {
      const res = await getClubGamification(clubId, { signal: controllerRef.current.signal });
      if (requestId !== requestIdRef.current) return;
      setState({ status: 'ready', data: res.data, errorMessage: '', canRetry: false });
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestId !== requestIdRef.current) return;
      if (axios.isAxiosError(error)) {
        const status = error.response?.status;
        if (status === 401) {
          setState({ status: 'unauthorized', data: null, errorMessage: 'Нужна авторизация', canRetry: false });
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
        setState({ status: 'error', data: null, errorMessage: 'Не удалось загрузить данные', canRetry: false });
        return;
      }
      setState({ status: 'error', data: null, errorMessage: 'Не удалось загрузить данные', canRetry: false });
    }
  }, [clubId]);

  useEffect(() => {
    void load();
    return () => controllerRef.current?.abort();
  }, [load]);

  return { ...state, reload: load };
}
