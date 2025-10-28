import { create } from 'zustand';

interface UiState {
  toasts: string[];
  addToast: (msg: string) => void;
  removeToast: (msg: string) => void;
}

/** Global UI store for toasts and theme. */
export const useUiStore = create<UiState>((set) => ({
  toasts: [],
  addToast: (msg) => set((s) => ({ toasts: [...s.toasts, msg] })),
  removeToast: (msg) => set((s) => ({ toasts: s.toasts.filter((t) => t !== msg) })),
}));
