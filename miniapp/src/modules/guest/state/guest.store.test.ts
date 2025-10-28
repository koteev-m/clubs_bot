import { useGuestStore } from './guest.store';

describe('guest.store', () => {
  it('holds state and clears', () => {
    const store = useGuestStore.getState();
    store.setClub(1);
    store.setNight('2024');
    store.setTable(2);
    store.setGuests(3);
    expect(useGuestStore.getState().guests).toBe(3);
    store.clear();
    expect(useGuestStore.getState().selectedClub).toBeUndefined();
    expect(useGuestStore.getState().guests).toBe(1);
  });
});
