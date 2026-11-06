import { useCallback, useEffect, useRef, useState } from 'react';
import { OnboardingError, onboardingRequest } from './onboarding.api';

export function useRemote<T>(
  credential: string,
  path: string | null,
  body?: object,
  { retainOnForeground = false }: { retainOnForeground?: boolean } = {},
) {
  const serialized = body === undefined ? undefined : JSON.stringify(body);
  const [attempt, setAttempt] = useState(0);
  const [foregroundEpoch, setForegroundEpoch] = useState(0);
  const [state, setState] = useState<{
    path?: string;
    serialized?: string;
    credential?: string;
    attempt?: number;
    foregroundEpoch?: number;
    data?: T;
    error?: unknown;
    loading: boolean;
  }>({ loading: true });
  useEffect(() => {
    if (!path) {
      setState({ loading: false });
      return;
    }
    const controller = new AbortController();
    const request = { path, serialized, credential, attempt, foregroundEpoch };
    onboardingRequest<T>(credential, path, {
      signal: controller.signal,
      body: serialized === undefined ? undefined : JSON.parse(serialized),
    })
      .then((data) => {
        if (!controller.signal.aborted)
          setState({ ...request, data, loading: false });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState({ ...request, error, loading: false });
      });
    return () => controller.abort();
  }, [credential, path, serialized, attempt, foregroundEpoch]);
  const refresh = useCallback(() => setAttempt((value) => value + 1), []);
  useEffect(() => {
    if (!path) return;
    const foreground = () => {
      if (!document.hidden) {
        if (retainOnForeground) setForegroundEpoch((value) => value + 1);
        else refresh();
      }
    };
    window.addEventListener('focus', foreground);
    window.addEventListener('pageshow', foreground);
    document.addEventListener('visibilitychange', foreground);
    return () => {
      window.removeEventListener('focus', foreground);
      window.removeEventListener('pageshow', foreground);
      document.removeEventListener('visibilitychange', foreground);
    };
  }, [path, refresh, retainOnForeground]);
  const current =
    state.path === path &&
    state.serialized === serialized &&
    state.credential === credential &&
    state.attempt === attempt &&
    (state.foregroundEpoch === foregroundEpoch ||
      (retainOnForeground && state.data !== undefined));
  return current
    ? { data: state.data, error: state.error, loading: state.loading, refresh }
    : { loading: path !== null, refresh };
}

interface PendingCommand<T> {
  path: string;
  body: object;
  method: string;
  key: string;
  unknown?: boolean;
  onSuccess: (data: T) => void;
}
export function useCommand<T>(credential: string) {
  const [state, setState] = useState<{
    saving: boolean;
    unknown: boolean;
    success: boolean;
    error?: unknown;
  }>({
    saving: false,
    unknown: false,
    success: false,
  });
  const pending = useRef<PendingCommand<T> | null>(null);
  const executing = useRef(false);
  const lifetime = useRef(new AbortController());
  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    executing.current = false;
    setState({ saving: false, unknown: false, success: false });
    return () => {
      controller.abort();
      pending.current = null;
    };
  }, [credential]);
  const execute = async (command: PendingCommand<T>) => {
    if (executing.current) return;
    executing.current = true;
    const controller = lifetime.current;
    setState({ saving: true, unknown: false, success: false });
    try {
      const result = await onboardingRequest<T>(credential, command.path, {
        method: command.method,
        body: command.body,
        key: command.key,
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      pending.current = null;
      setState({ saving: false, unknown: false, success: true });
      command.onSuccess(result);
    } catch (error) {
      if (controller.signal.aborted) return;
      const unknown =
        error instanceof OnboardingError &&
        (command.unknown || error.code !== 'invitation_link_unavailable') &&
        (error.status === 0 || error.status >= 500);
      command.unknown = unknown;
      if (!unknown) pending.current = null;
      setState({ saving: false, unknown, success: false, error });
    } finally {
      if (!controller.signal.aborted) executing.current = false;
    }
  };
  return {
    ...state,
    blocked: state.saving || state.unknown,
    run(
      path: string,
      body: object,
      onSuccess: (data: T) => void,
      method = 'POST',
    ) {
      if (pending.current || state.saving) return;
      const command = {
        path,
        body,
        method,
        key: crypto.randomUUID(),
        onSuccess,
      };
      pending.current = command;
      void execute(command);
    },
    retry() {
      if (pending.current) void execute(pending.current);
    },
    reset() {
      // A pending unknown command must be retried with its original intent.
      if (pending.current || executing.current) return;
      setState({ saving: false, unknown: false, success: false });
    },
  };
}
