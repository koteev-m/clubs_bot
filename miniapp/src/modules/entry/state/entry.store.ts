import { create } from 'zustand';

interface EntryState {
  lastResult?: string;
  setResult: (r: string) => void;
}

/** Store for Entry mode check-in results. */
export const useEntryStore = create<EntryState>((set) => ({
  lastResult: undefined,
  setResult: (r) => set({ lastResult: r }),
}));
