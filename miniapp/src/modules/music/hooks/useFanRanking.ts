import { useCallback, useEffect, useRef, useState } from 'react';
import { FanRankingDto, getFanRanking, MusicApiError } from '../api/music.api';
import { isRequestCanceled } from '../../../shared/api/error';

type FanRankingStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

interface FanRankingState {
  status: FanRankingStatus;
  data: FanRankingDto | null;
  errorMessage: string;
  canRetry: boolean;
}

const defaultState: FanRankingState = {
  status: 'idle',
  data: null,
  errorMessage: '',
  canRetry: false,
};

export function useFanRanking(clubId?: number, windowDays = 30, enabled = true) {
  const [state, setState] = useState<FanRankingState>(defaultState);
  const controllerRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  const load = useCallback(async () => {
    if (!enabled || !clubId) {
      controllerRef.current?.abort();
      setState(defaultState);
      return;
    }
    const requestId = ++requestIdRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setState((prev) => ({ ...prev, status: 'loading', errorMessage: '', canRetry: false }));
    try {
      const data = await getFanRanking({ clubId, windowDays }, controller.signal);
      if (requestIdRef.current !== requestId) return;
      setState({ status: 'ready', data, errorMessage: '', canRetry: false });
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestIdRef.current !== requestId) return;
      if (error instanceof MusicApiError) {
        if (error.status === 401) {
          setState({ status: 'unauthorized', data: null, errorMessage: 'Нужна авторизация', canRetry: false });
          return;
        }
        if (error.status === 403) {
          setState({ status: 'forbidden', data: null, errorMessage: 'Нет доступа', canRetry: false });
          return;
        }
        if (!error.status || error.status >= 500) {
          setState({
            status: 'error',
            data: null,
            errorMessage: !error.status ? 'Не удалось связаться с сервером' : 'Сервис временно недоступен',
            canRetry: true,
          });
          return;
        }
      }
      setState({ status: 'error', data: null, errorMessage: 'Не удалось загрузить fan ranking', canRetry: true });
    }
  }, [clubId, enabled, windowDays]);

  useEffect(() => {
    if (!enabled) return;
    void load();
    return () => controllerRef.current?.abort();
  }, [enabled, load]);

  useEffect(() => {
    if (!enabled) {
      controllerRef.current?.abort();
    }
  }, [enabled]);

  return {
    ...state,
    reload: load,
  };
}
