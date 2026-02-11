import { useCallback, useEffect, useRef, useState } from 'react';
import { BattleDto, getCurrentBattle, MusicApiError } from '../api/music.api';
import { isRequestCanceled } from '../../../shared/api/error';

export type BattleStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

interface BattleState {
  status: BattleStatus;
  battle: BattleDto | null;
  errorMessage: string;
  canRetry: boolean;
}

const defaultState: BattleState = {
  status: 'idle',
  battle: null,
  errorMessage: '',
  canRetry: false,
};

export function useBattle(clubId?: number, enabled = true) {
  const [state, setState] = useState<BattleState>(defaultState);
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
      const battle = await getCurrentBattle(clubId, controller.signal);
      if (requestIdRef.current !== requestId) return;
      setState({ status: 'ready', battle, errorMessage: '', canRetry: false });
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (requestIdRef.current !== requestId) return;
      if (error instanceof MusicApiError) {
        if (error.status === 401) {
          setState({ status: 'unauthorized', battle: null, errorMessage: 'Нужна авторизация', canRetry: false });
          return;
        }
        if (error.status === 403) {
          setState({ status: 'forbidden', battle: null, errorMessage: 'Нет доступа', canRetry: false });
          return;
        }
        if (error.status === 404) {
          setState({ status: 'ready', battle: null, errorMessage: '', canRetry: false });
          return;
        }
        if (!error.status || error.status >= 500) {
          setState({
            status: 'error',
            battle: null,
            errorMessage: !error.status ? 'Не удалось связаться с сервером' : 'Сервис временно недоступен',
            canRetry: true,
          });
          return;
        }
      }
      setState({ status: 'error', battle: null, errorMessage: 'Не удалось загрузить battle', canRetry: true });
    }
  }, [clubId, enabled]);

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
