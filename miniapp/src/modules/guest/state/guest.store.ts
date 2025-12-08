import { create } from 'zustand';

interface GuestState {
  selectedClub?: number;
  selectedNight?: string;
  selectedEventId?: number;
  selectedTable?: number;
  selectedTableCapacity?: number;
  guests: number;
  setClub: (id: number) => void;
  setNight: (startUtc: string, eventId?: number) => void;
  setTable: (id?: number, capacity?: number) => void;
  setGuests: (count: number) => void;
  clear: () => void;
}

/** Store for Guest mode selections. */
export const useGuestStore = create<GuestState>((set) => ({
  selectedClub: undefined,
  selectedNight: undefined,
  selectedEventId: undefined,
  selectedTable: undefined,
  selectedTableCapacity: undefined,
  guests: 1,
  setClub: (id) =>
    set({ selectedClub: id, selectedNight: undefined, selectedEventId: undefined, selectedTable: undefined, selectedTableCapacity: undefined }),
  setNight: (startUtc, eventId) =>
    set({ selectedNight: startUtc, selectedEventId: eventId, selectedTable: undefined, selectedTableCapacity: undefined }),
  setTable: (id, capacity) => set({ selectedTable: id, selectedTableCapacity: capacity }),
  setGuests: (count) => set({ guests: count }),
  clear: () =>
    set({ selectedClub: undefined, selectedNight: undefined, selectedEventId: undefined, selectedTable: undefined, selectedTableCapacity: undefined, guests: 1 }),
}));
