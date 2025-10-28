import { useEffect, useRef } from 'react';

/**
 * Simple debounced cache invalidation hook.
 */
export function useDebouncedCache<T>(value: T, ms: number, onExpire: () => void) {
  const timer = useRef<number>();
  useEffect(() => {
    clearTimeout(timer.current);
    timer.current = window.setTimeout(onExpire, ms);
    return () => clearTimeout(timer.current);
  }, [value, ms, onExpire]);
}
