import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminApiError } from '../api/admin.api';
import { getStoryDetails, listStories, StoryDetailsResponse, StoryListItem } from '../api/adminAnalytics.api';
import { isRequestCanceled } from '../../../shared/api/error';

type StoriesStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

type StoryDetailsStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized' | 'forbidden';

interface StoriesState {
  status: StoriesStatus;
  items: StoryListItem[];
  errorMessage: string;
  canRetry: boolean;
  incompleteMap: Record<string, boolean | null>;
}

interface StoryDetailsState {
  status: StoryDetailsStatus;
  data: StoryDetailsResponse | null;
  errorMessage: string;
  canRetry: boolean;
}

const extractIncompleteFlag = (payload: unknown): boolean | null => {
  if (!payload || typeof payload !== 'object') return null;
  if (!('meta' in payload)) return null;
  const meta = (payload as { meta?: { hasIncompleteData?: boolean } }).meta;
  if (!meta || typeof meta !== 'object') return null;
  if (typeof meta.hasIncompleteData !== 'boolean') return null;
  return meta.hasIncompleteData;
};

export function useStories(
  clubId?: number,
  limit = 20,
  offset = 0,
  selectedNight?: string,
  onForbidden?: () => void,
  enabled = true,
) {
  const [listState, setListState] = useState<StoriesState>({
    status: 'idle',
    items: [],
    errorMessage: '',
    canRetry: false,
    incompleteMap: {},
  });
  const [detailsState, setDetailsState] = useState<StoryDetailsState>({
    status: 'idle',
    data: null,
    errorMessage: '',
    canRetry: false,
  });

  const listControllerRef = useRef<AbortController | null>(null);
  const listRequestIdRef = useRef(0);
  const detailsControllerRef = useRef<AbortController | null>(null);
  const detailsRequestIdRef = useRef(0);

  const loadIncompleteFlags = useCallback(
    async (items: StoryListItem[], signal: AbortSignal, requestId: number) => {
      if (!clubId || items.length === 0) return;
      const results = await Promise.all(
        items.map(async (story) => {
          try {
            const detail = await getStoryDetails(clubId, story.nightStartUtc, signal);
            return { key: story.nightStartUtc, value: extractIncompleteFlag(detail.payload) };
          } catch (error) {
            if (isRequestCanceled(error)) return null;
            return { key: story.nightStartUtc, value: null };
          }
        }),
      );
      if (listRequestIdRef.current !== requestId) return;
      setListState((prev) => {
        const nextMap = { ...prev.incompleteMap };
        results.forEach((entry) => {
          if (!entry) return;
          nextMap[entry.key] = entry.value;
        });
        return { ...prev, incompleteMap: nextMap };
      });
    },
    [clubId],
  );

  const loadList = useCallback(async () => {
    if (!enabled) return;
    if (!clubId) {
      listControllerRef.current?.abort();
      setListState({ status: 'idle', items: [], errorMessage: '', canRetry: false, incompleteMap: {} });
      return;
    }
    const requestId = ++listRequestIdRef.current;
    listControllerRef.current?.abort();
    const controller = new AbortController();
    listControllerRef.current = controller;
    setListState((prev) => ({ ...prev, status: 'loading', errorMessage: '', canRetry: false }));
    try {
      const response = await listStories(clubId, limit, offset, controller.signal);
      if (listRequestIdRef.current !== requestId) return;
      const initialMap = response.stories.reduce<Record<string, boolean | null>>((acc, story) => {
        acc[story.nightStartUtc] = null;
        return acc;
      }, {});
      setListState({
        status: 'ready',
        items: response.stories,
        errorMessage: '',
        canRetry: false,
        incompleteMap: initialMap,
      });
      await loadIncompleteFlags(response.stories, controller.signal, requestId);
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (listRequestIdRef.current !== requestId) return;
      if (error instanceof AdminApiError) {
        const status = error.status;
        if (status === 401) {
          setListState({ status: 'unauthorized', items: [], errorMessage: 'Нужна авторизация', canRetry: false, incompleteMap: {} });
          return;
        }
        if (status === 403) {
          onForbidden?.();
          setListState({ status: 'forbidden', items: [], errorMessage: 'Нет доступа', canRetry: false, incompleteMap: {} });
          return;
        }
        if (!status) {
          setListState({
            status: 'error',
            items: [],
            errorMessage: 'Не удалось связаться с сервером',
            canRetry: true,
            incompleteMap: {},
          });
          return;
        }
        if (status >= 500) {
          setListState({
            status: 'error',
            items: [],
            errorMessage: 'Сервис временно недоступен',
            canRetry: true,
            incompleteMap: {},
          });
          return;
        }
        setListState({ status: 'error', items: [], errorMessage: error.message, canRetry: false, incompleteMap: {} });
        return;
      }
      setListState({ status: 'error', items: [], errorMessage: 'Не удалось загрузить истории', canRetry: false, incompleteMap: {} });
    }
  }, [clubId, enabled, limit, loadIncompleteFlags, offset, onForbidden]);

  const loadDetails = useCallback(async () => {
    if (!enabled) return;
    if (!clubId || !selectedNight) {
      detailsControllerRef.current?.abort();
      setDetailsState({ status: 'idle', data: null, errorMessage: '', canRetry: false });
      return;
    }
    const requestId = ++detailsRequestIdRef.current;
    detailsControllerRef.current?.abort();
    detailsControllerRef.current = new AbortController();
    setDetailsState((prev) => ({ ...prev, status: 'loading', errorMessage: '', canRetry: false }));
    try {
      const detail = await getStoryDetails(clubId, selectedNight, detailsControllerRef.current.signal);
      if (detailsRequestIdRef.current !== requestId) return;
      setDetailsState({ status: 'ready', data: detail, errorMessage: '', canRetry: false });
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (detailsRequestIdRef.current !== requestId) return;
      if (error instanceof AdminApiError) {
        const status = error.status;
        if (status === 401) {
          setDetailsState({ status: 'unauthorized', data: null, errorMessage: 'Нужна авторизация', canRetry: false });
          return;
        }
        if (status === 403) {
          onForbidden?.();
          setDetailsState({ status: 'forbidden', data: null, errorMessage: 'Нет доступа', canRetry: false });
          return;
        }
        if (!status) {
          setDetailsState({ status: 'error', data: null, errorMessage: 'Не удалось связаться с сервером', canRetry: true });
          return;
        }
        if (status >= 500) {
          setDetailsState({ status: 'error', data: null, errorMessage: 'Сервис временно недоступен', canRetry: true });
          return;
        }
        setDetailsState({ status: 'error', data: null, errorMessage: error.message, canRetry: false });
        return;
      }
      setDetailsState({ status: 'error', data: null, errorMessage: 'Не удалось загрузить детали', canRetry: false });
    }
  }, [clubId, enabled, onForbidden, selectedNight]);

  useEffect(() => {
    if (!enabled) return;
    void loadList();
    return () => listControllerRef.current?.abort();
  }, [enabled, loadList]);

  useEffect(() => {
    if (!enabled) return;
    void loadDetails();
    return () => detailsControllerRef.current?.abort();
  }, [enabled, loadDetails]);

  useEffect(() => {
    if (!enabled) {
      listControllerRef.current?.abort();
      detailsControllerRef.current?.abort();
    }
  }, [enabled]);

  return {
    list: listState,
    details: detailsState,
    reloadList: loadList,
    reloadDetails: loadDetails,
  };
}
