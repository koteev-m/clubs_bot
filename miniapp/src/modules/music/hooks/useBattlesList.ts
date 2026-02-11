import { useCallback, useEffect, useRef, useState } from 'react';
import { BattleDto, listBattles, MusicApiError } from '../api/music.api';
import { isRequestCanceled } from '../../../shared/api/error';

type BattlesListStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

interface BattlesListState {
  status: BattlesListStatus;
  items: BattleDto[];
  errorMessage: string;
  canRetry: boolean;
}

const defaultState: BattlesListState = {
  status: 'idle',
  items: [],
  errorMessage: '',
  canRetry: false,
};

export function useBattlesList(clubId?: number, limit = 20, offset = 0, enabled = true) {
  const [state, setState] = useState<BattlesListState>(defaultState);
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
      const items = await listBattles({ clubId, limit, offset }, controller.signal);
      if (requestIdRef.current !== requestId) return;
      setState({ status: 'ready', items, errorMessage: '', canRetry: false });
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestIdRef.current !== requestId) return;
      if (error instanceof MusicApiError) {
        if (error.status === 401) {
          setState({ status: 'unauthorized', items: [], errorMessage: 'Нужна авторизация', canRetry: false });
          return;
        }
        if (error.status === 403) {
          setState({ status: 'forbidden', items: [], errorMessage: 'Нет доступа', canRetry: false });
          return;
        }
        if (!error.status || error.status >= 500) {
          setState({
            status: 'error',
            items: [],
            errorMessage: !error.status ? 'Не удалось связаться с сервером' : 'Сервис временно недоступен',
            canRetry: true,
          });
          return;
        }
      }
      setState({ status: 'error', items: [], errorMessage: 'Не удалось загрузить список battles', canRetry: true });
    }
  }, [clubId, enabled, limit, offset]);

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
