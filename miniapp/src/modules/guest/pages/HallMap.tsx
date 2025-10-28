import { useEffect, useState } from 'react';
import { useGuestStore } from '../state/guest.store';
import { http } from '../../../shared/api/http';
import { TableAvailabilityDto } from '../../../shared/types';
import { useHallHotspots } from '../hooks/useHallHotspots';

/** Shows hall image with clickable table polygons. */
export default function HallMap() {
  const { selectedClub, selectedNight, setTable } = useGuestStore();
  const [tables, setTables] = useState<TableAvailabilityDto[]>([]);
  const geo = useHallHotspots(`/assets/halls/club_${selectedClub}.geojson`);
  const imageSrc = `/assets/halls/club_${selectedClub}.png`;

  useEffect(() => {
    if (!selectedClub || !selectedNight) return;
    http
      .get<TableAvailabilityDto[]>(`/api/clubs/${selectedClub}/nights/${selectedNight}/tables/free`)
      .then((r) => setTables(r.data));
  }, [selectedClub, selectedNight]);

  return (
    <div className="relative">
      <img src={imageSrc} alt="hall" />
      <svg className="absolute inset-0">
        {tables.map((t) => {
          const poly = geo.getPolygon(t.id);
          const color = t.status === 'FREE' ? 'green' : t.status === 'HELD' ? 'yellow' : 'red';
          const points = poly.map((p) => p.join(',')).join(' ');
          return (
            <polygon
              key={t.id}
              points={points}
              fill={color}
              fillOpacity={0.4}
              stroke="black"
              onClick={() => setTable(t.id)}
            />
          );
        })}
      </svg>
    </div>
  );
}
