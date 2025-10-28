import { useGuestStore } from '../state/guest.store';
import { formatRUB } from '../../../shared/lib/format';

/** Component allowing to choose guest count. */
export default function GuestsPicker() {
  const { guests, setGuests } = useGuestStore();
  const minDeposit = 1000;
  return (
    <div className="space-y-2">
      <input
        type="number"
        min={1}
        value={guests}
        onChange={(e) => setGuests(Number(e.target.value))}
        className="border p-2 w-full"
      />
      <div>Итого депозит: {formatRUB(guests * minDeposit)}</div>
    </div>
  );
}
