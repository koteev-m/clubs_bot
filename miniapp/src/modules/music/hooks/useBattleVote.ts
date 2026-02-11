import { useCallback, useRef, useState } from 'react';
import { BattleDto, MusicApiError, voteInBattle } from '../api/music.api';
import { isRequestCanceled } from '../../../shared/api/error';

type VoteStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

interface VoteState {
  status: VoteStatus;
  data: BattleDto | null;
  errorMessage: string;
}

const defaultState: VoteState = {
  status: 'idle',
  data: null,
  errorMessage: '',
};

export function useBattleVote() {
  const [state, setState] = useState<VoteState>(defaultState);
  const controllerRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  const vote = useCallback(async (battleId: number, chosenItemId: number) => {
    const requestId = ++requestIdRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setState({ status: 'loading', data: null, errorMessage: '' });
    try {
      const data = await voteInBattle(battleId, chosenItemId, controller.signal);
      if (requestIdRef.current !== requestId) return null;
      setState({ status: 'ready', data, errorMessage: '' });
      return data;
    } catch (error) {
      if (isRequestCanceled(error)) return null;
      if (requestIdRef.current !== requestId) return null;
      if (error instanceof MusicApiError) {
        if (error.status === 401) {
          setState({ status: 'unauthorized', data: null, errorMessage: 'Нужна авторизация' });
          return null;
        }
        if (error.status === 403) {
          setState({ status: 'forbidden', data: null, errorMessage: 'Нет доступа' });
          return null;
        }
        if (error.status === 409) {
          setState({ status: 'error', data: null, errorMessage: 'Голосование уже завершено' });
          return null;
        }
      }
      setState({ status: 'error', data: null, errorMessage: 'Не удалось отправить голос' });
      return null;
    }
  }, []);

  const reset = useCallback(() => {
    controllerRef.current?.abort();
    setState(defaultState);
  }, []);

  return {
    ...state,
    vote,
    reset,
  };
}
