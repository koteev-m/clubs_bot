import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminApiError } from '../api/admin.api';
import { AdminShiftReportDetails, getShiftReport } from '../api/adminFinance.api';
import { isRequestCanceled } from '../../../shared/api/error';

type ShiftReportStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

interface ShiftReportState {
  status: ShiftReportStatus;
  data: AdminShiftReportDetails | null;
  errorMessage: string;
  canRetry: boolean;
}

export function useShiftReport(clubId?: number, nightKey?: string, onForbidden?: () => void) {
  const [state, setState] = useState<ShiftReportState>({
    status: 'idle',
    data: null,
    errorMessage: '',
    canRetry: false,
  });
  const controllerRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  const load = useCallback(async () => {
    if (!clubId || !nightKey) {
      controllerRef.current?.abort();
      setState({ status: 'idle', data: null, errorMessage: '', canRetry: false });
      return;
    }
    const requestId = ++requestIdRef.current;
    controllerRef.current?.abort();
    controllerRef.current = new AbortController();
    setState((prev) => ({ ...prev, status: 'loading', errorMessage: '', canRetry: false }));
    try {
      const details = await getShiftReport(clubId, nightKey, controllerRef.current.signal);
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
          setState({
            status: 'error',
            data: null,
            errorMessage: 'Не удалось связаться с сервером',
            canRetry: true,
          });
          return;
        }
        if (status >= 500) {
          setState({
            status: 'error',
            data: null,
            errorMessage: 'Сервис временно недоступен',
            canRetry: true,
          });
          return;
        }
        setState({ status: 'error', data: null, errorMessage: error.message, canRetry: false });
        return;
      }
      setState({ status: 'error', data: null, errorMessage: 'Не удалось загрузить данные', canRetry: false });
    }
  }, [clubId, nightKey, onForbidden]);

  useEffect(() => {
    void load();
    return () => controllerRef.current?.abort();
  }, [load]);

  return { ...state, reload: load };
}
