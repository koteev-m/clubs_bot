import { useEffect } from 'react';
import { useUiStore } from '../shared/store/ui';

/** Renders toast messages from global store. */
export default function ToastHost() {
  const { toasts, removeToast } = useUiStore();

  useEffect(() => {
    const timers = toasts.map((t) => setTimeout(() => removeToast(t), 3000));
    return () => timers.forEach(clearTimeout);
  }, [toasts, removeToast]);

  return (
    <div className="fixed bottom-2 left-1/2 -translate-x-1/2 space-y-2">
      {toasts.map((t) => (
        <div key={t} className="bg-black/70 text-white px-3 py-1 rounded">
          {t}
        </div>
      ))}
    </div>
  );
}
