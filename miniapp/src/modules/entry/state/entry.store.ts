import { create } from 'zustand';

type EntryCheckinResult = 'ARRIVED' | 'LATE' | 'DENIED';

interface EntryState {
  lastResult?: EntryCheckinResult;
  setResult: (r: EntryCheckinResult) => void;
}

/** Store for Entry mode check-in results. */
export const useEntryStore = create<EntryState>((set) => ({
  lastResult: undefined,
  setResult: (r) => set({ lastResult: r }),
}));
