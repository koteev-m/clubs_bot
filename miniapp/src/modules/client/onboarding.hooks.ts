import { useCallback, useEffect, useRef, useState } from 'react';
import { OnboardingError, onboardingRequest } from './onboarding.api';
import { useClubRequestScope } from './clubRequestScope';

export function useRemote<T>(
  credential: string,
  path: string | null,
  body?: object,
  {
    retainOnForeground = false,
    onSuccess,
    onError,
  }: {
    retainOnForeground?: boolean;
    onSuccess?: (data: T) => void;
    onError?: (error: unknown) => void;
  } = {},
) {
  const scope = useClubRequestScope();
  const sequence = useRef({ value: 0 });
  const callbacks = useRef({ onSuccess, onError });
  callbacks.current = { onSuccess, onError };
  const activeRead = useRef<AbortController>();
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
  const cancel = useCallback(() => {
    sequence.current.value++;
    activeRead.current?.abort();
    setState({ loading: false });
  }, []);
  useEffect(() => {
    if (!path) {
      setState({ loading: false });
      return;
    }
    const controller = new AbortController();
    const lifecycle = sequence.current;
    const requestSequence = ++lifecycle.value;
    const ticket = scope?.capture(credential, path);
    const current = () =>
      lifecycle.value === requestSequence &&
      !controller.signal.aborted &&
      (!scope || scope.current(ticket));
    const untrack = scope?.track(ticket, () => controller.abort());
    const cleanup = () => {
      lifecycle.value++;
      controller.abort();
      untrack?.();
    };
    activeRead.current = controller;
    if (!current()) return cleanup;
    const request = {
      path,
      serialized,
      credential,
      attempt,
      foregroundEpoch,
    };
    let responseReceived = false;
    onboardingRequest<T>(credential, path, {
      signal: controller.signal,
      isCurrent: current,
      body: serialized === undefined ? undefined : JSON.parse(serialized),
    })
      .then((data) => {
        responseReceived = true;
        if (!current()) return;
        callbacks.current.onSuccess?.(data);
        if (!current()) return;
        setState({ ...request, data, loading: false });
      })
      .catch((error) => {
        if (!current()) return;
        if (!responseReceived)
          scope?.reject(
            ticket,
            error,
            path,
            serialized === undefined ? 'GET' : 'POST',
          );
        callbacks.current.onError?.(error);
        setState({ ...request, error, loading: false });
      })
      .finally(untrack);
    return () => {
      lifecycle.value++;
      controller.abort();
      untrack?.();
    };
  }, [credential, path, serialized, attempt, foregroundEpoch, scope]);
  const refresh = useCallback(() => {
    cancel();
    setAttempt((value) => value + 1);
  }, [cancel]);
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
    ? {
        data: state.data,
        error: state.error,
        loading: state.loading,
        revalidating: state.foregroundEpoch !== foregroundEpoch,
        refresh,
        cancel,
      }
    : { loading: path !== null, revalidating: false, refresh, cancel };
}

interface PendingCommand<T> {
  path: string;
  body: object;
  method: string;
  key: string;
  unknown?: boolean;
  onSuccess: (data: T) => void | boolean;
}
export function useCommand<T>(
  credential: string,
  {
    onError,
    preserveUnknownOnRetryFailure = false,
  }: {
    onError?: (error: unknown) => void;
    preserveUnknownOnRetryFailure?: boolean;
  } = {},
) {
  const scope = useClubRequestScope();
  const sequence = useRef({ value: 0 });
  const callbacks = useRef({ onError });
  callbacks.current = { onError };
  const [state, setState] = useState<{
    saving: boolean;
    unknown: boolean;
    success: boolean;
    resolving?: boolean;
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
    const lifecycle = sequence.current;
    lifecycle.value++;
    lifetime.current = controller;
    executing.current = false;
    setState({ saving: false, unknown: false, success: false });
    return () => {
      lifecycle.value++;
      controller.abort();
      pending.current = null;
    };
  }, [credential]);
  const execute = async (command: PendingCommand<T>) => {
    if (executing.current) return;
    executing.current = true;
    const controller = lifetime.current;
    const commandSequence = sequence.current.value;
    const lifecycle = sequence.current;
    const ticket = scope?.capture(credential, command.path);
    const current = () =>
      lifecycle.value === commandSequence &&
      !controller.signal.aborted &&
      (!scope || scope.current(ticket));
    const untrack = scope?.track(ticket, () => controller.abort());
    if (!current()) {
      untrack?.();
      executing.current = false;
      return;
    }
    setState({ saving: true, unknown: false, success: false });
    let responseReceived = false;
    try {
      const result = await onboardingRequest<T>(credential, command.path, {
        method: command.method,
        body: command.body,
        key: command.key,
        signal: controller.signal,
        isCurrent: current,
      });
      responseReceived = true;
      if (!current()) return;
      if (!preserveUnknownOnRetryFailure) pending.current = null;
      const accepted = command.onSuccess(result);
      const unknown =
        preserveUnknownOnRetryFailure &&
        Boolean(command.unknown) &&
        accepted === false;
      command.unknown = unknown;
      if (!unknown) pending.current = null;
      setState({
        saving: false,
        unknown,
        success: !unknown && accepted !== false,
      });
    } catch (error) {
      if (!current()) return;
      if (!responseReceived)
        scope?.reject(ticket, error, command.path, command.method);
      const unknown =
        (preserveUnknownOnRetryFailure && Boolean(command.unknown)) ||
        (error instanceof OnboardingError &&
          (command.unknown || error.code !== 'invitation_link_unavailable') &&
          (error.status === 0 || error.status >= 500));
      command.unknown = unknown;
      if (!unknown) pending.current = null;
      callbacks.current.onError?.(error);
      setState({ saving: false, unknown, success: false, error });
    } finally {
      untrack?.();
      if (lifecycle.value === commandSequence) executing.current = false;
    }
  };
  const retry = (onSuccess?: PendingCommand<T>['onSuccess']) => {
    if (!pending.current || executing.current) return;
    if (onSuccess) pending.current.onSuccess = onSuccess;
    void execute(pending.current);
  };
  // Receipt lookup is a business read: it never enters execute/retry. The
  // original request and key stay private and unchanged while outcome is unknown.
  const resolve = async <R>(
    path: string,
    onResult: (data: R) => boolean | 'obsolete',
  ) => {
    const command = pending.current;
    if (!command?.unknown || executing.current) return;
    executing.current = true;
    const controller = lifetime.current;
    const lifecycle = sequence.current;
    const requestSequence = lifecycle.value;
    const ticket = scope?.capture(credential, path);
    const current = () =>
      lifecycle.value === requestSequence &&
      !controller.signal.aborted &&
      pending.current === command &&
      (!scope || scope.current(ticket));
    const untrack = scope?.track(ticket, () => controller.abort());
    if (!current()) {
      untrack?.();
      executing.current = false;
      return;
    }
    setState({ saving: false, unknown: true, success: false, resolving: true });
    let responseReceived = false;
    try {
      const data = await onboardingRequest<R>(credential, path, {
        method: 'POST',
        body: command.body,
        key: command.key,
        signal: controller.signal,
        isCurrent: current,
      });
      responseReceived = true;
      if (!current()) return;
      const completed = onResult(data);
      if (!current()) return;
      if (completed) pending.current = null;
      setState({
        saving: false,
        unknown: !completed,
        success: completed === true,
      });
    } catch (error) {
      if (!current()) return;
      if (!responseReceived) scope?.reject(ticket, error, path, 'POST');
      callbacks.current.onError?.(error);
      setState({ saving: false, unknown: true, success: false, error });
    } finally {
      untrack?.();
      if (lifecycle.value === requestSequence) executing.current = false;
    }
  };
  return {
    ...state,
    resolving: Boolean(state.resolving),
    resolve,
    blocked: state.saving || state.unknown,
    run(
      path: string,
      body: object,
      onSuccess: (data: T) => void | boolean,
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
      retry();
    },
    retryWith: retry,
    reset() {
      // A pending unknown command must be retried with its original intent.
      if (pending.current || executing.current) return;
      setState({ saving: false, unknown: false, success: false });
    },
  };
}
