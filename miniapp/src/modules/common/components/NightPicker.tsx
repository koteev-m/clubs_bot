import { useGuestStore } from '../../guest/state/guest.store';
import { useEffect, useState } from 'react';
import { http } from '../../../shared/api/http';
import { NightDto } from '../../../shared/types';

/** Picker component for night selection. */
export default function NightPicker() {
  const { selectedClub, selectedNight, setNight } = useGuestStore();
  const [nights, setNights] = useState<NightDto[]>([]);

  useEffect(() => {
    if (!selectedClub) return;
    http.get<NightDto[]>(`/api/clubs/${selectedClub}/nights?limit=8`).then((r) => setNights(r.data));
  }, [selectedClub]);

  if (!selectedClub) return null;

  return (
    <select value={selectedNight ?? ''} onChange={(e) => setNight(e.target.value)} className="p-2">
      <option value="" disabled>
        Select night
      </option>
      {nights.map((n) => (
        <option key={n.startUtc} value={n.startUtc}>
          {n.name}
        </option>
      ))}
    </select>
  );
}
