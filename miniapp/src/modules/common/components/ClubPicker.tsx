import { useGuestStore } from '../../guest/state/guest.store';
import { useEffect, useState } from 'react';
import { http } from '../../../shared/api/http';
import { ClubDto } from '../../../shared/types';

/** Picker component for club selection. */
export default function ClubPicker() {
  const { selectedClub, setClub } = useGuestStore();
  const [clubs, setClubs] = useState<ClubDto[]>([]);

  useEffect(() => {
    http.get<ClubDto[]>('/api/clubs').then((r) => setClubs(r.data));
  }, []);

  return (
    <select value={selectedClub ?? ''} onChange={(e) => setClub(Number(e.target.value))} className="p-2">
      <option value="" disabled>
        Select club
      </option>
      {clubs.map((c) => (
        <option key={c.id} value={c.id}>
          {c.name}
        </option>
      ))}
    </select>
  );
}
