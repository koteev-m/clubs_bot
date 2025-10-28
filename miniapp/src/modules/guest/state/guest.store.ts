import { create } from 'zustand';

interface GuestState {
  selectedClub?: number;
  selectedNight?: string;
  selectedTable?: number;
  guests: number;
  setClub: (id: number) => void;
  setNight: (startUtc: string) => void;
  setTable: (id: number) => void;
  setGuests: (count: number) => void;
  clear: () => void;
}

/** Store for Guest mode selections. */
export const useGuestStore = create<GuestState>((set) => ({
  selectedClub: undefined,
  selectedNight: undefined,
  selectedTable: undefined,
  guests: 1,
  setClub: (id) => set({ selectedClub: id, selectedNight: undefined, selectedTable: undefined }),
  setNight: (startUtc) => set({ selectedNight: startUtc, selectedTable: undefined }),
  setTable: (id) => set({ selectedTable: id }),
  setGuests: (count) => set({ guests: count }),
  clear: () => set({ selectedClub: undefined, selectedNight: undefined, selectedTable: undefined, guests: 1 }),
}));
