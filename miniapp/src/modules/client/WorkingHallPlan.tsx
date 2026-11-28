import { useEffect, useState } from 'react';
import HallPlanStage from '../admin/pages/HallPlanStage';
import { onboardingRequest } from './onboarding.api';
import { useClubRequestScope } from './clubRequestScope';
import { tableName } from './clubNightDesk.types';
import type { Hall, Table, TableStatus } from './clubNightDesk.types';

const badges: Record<TableStatus, { label: string; symbol: string; color: string }> = {
  free: { label: 'Свободен', symbol: '●', color: 'bg-green-100 text-green-900' },
  reserved: { label: 'Бронь', symbol: '●', color: 'bg-yellow-100 text-yellow-900' },
  seated: { label: 'Занят', symbol: '●', color: 'bg-red-100 text-red-900' },
  unavailable: { label: 'Недоступен', symbol: '×', color: 'bg-gray-100 text-gray-800' },
  stopped: { label: 'Стоп продаж', symbol: '×', color: 'bg-purple-100 text-purple-900' },
};

export default function WorkingHallPlan({
  hall, tables, credential, clubId, refresh, grid, choose,
}: {
  hall: Hall;
  tables: Table[];
  credential: string;
  clubId: string;
  refresh: () => void;
  grid: () => void;
  choose: (table: Table) => void;
}) {
  const scope = useClubRequestScope();
  const path = hall.plan &&
    '/api/client/clubs/' + clubId + '/operations/halls/' + hall.id + '/plan/' +
    hall.plan.layoutRevision + '/' + hall.plan.sha256;
  const [retry, setRetry] = useState(0);
  const [zoom, setZoom] = useState(1);
  const [result, setResult] = useState<{ url?: string; error?: boolean }>();
  useEffect(() => {
    if (!path) return;
    setResult(undefined);
    const controller = new AbortController();
    const ticket = scope?.capture(credential, path);
    const untrack = scope?.track(ticket, () => controller.abort());
    const current = () => !controller.signal.aborted && (!scope || scope.current(ticket));
    let url: string | undefined;
    void onboardingRequest<Blob>(credential, path, {
      signal: controller.signal, isCurrent: current, responseType: 'blob',
    }).then((blob) => {
      if (!current()) return;
      if (!['image/png', 'image/jpeg', 'image/webp'].includes(blob.type) || !blob.size) {
        setResult({ error: true });
        return;
      }
      url = URL.createObjectURL(blob);
      setResult({ url });
    }).catch((error: unknown) => {
      if (!current()) return;
      scope?.reject(ticket, error, path, 'GET');
      if (current()) setResult({ error: true });
    }).finally(untrack);
    return () => {
      controller.abort();
      untrack?.();
      if (url) URL.revokeObjectURL(url);
    };
  }, [credential, path, retry, scope]);

  if (!path) return <p className="po-muted">План зала не загружен. Столы доступны в сетке ниже.</p>;
  if (result?.error) return <div className="po-notice" role="alert">
    <p>Не удалось загрузить сохранённый план.</p>
    <button type="button" className="ob-button" onClick={() => { setRetry((value) => value + 1); refresh(); }}>Загрузить повторно</button>
    <button type="button" className="ob-button" onClick={grid}>К сетке столов</button>
  </div>;
  if (!result?.url) return <p role="status">Загружаем план зала…</p>;
  return (
    <section aria-label={'Схема · ' + hall.name} className="night-plan">
      <div className="night-plan-tools">
        <button type="button" className="ob-button" aria-label="Уменьшить схему" disabled={zoom === 1}
          onClick={() => setZoom((value) => value - .5)}>−</button>
        <output>{Math.round(zoom * 100)}%</output>
        <button type="button" className="ob-button" aria-label="Увеличить схему" disabled={zoom === 3}
          onClick={() => setZoom((value) => value + .5)}>+</button>
      </div>
      <div className="night-plan-scroll">
        <div style={{ width: zoom * 100 + '%', padding: 22 }}>
          <HallPlanStage<string> planUrl={result.url} readOnly selectedTableId={null} markerSize={44}
            onImageError={() => setResult({ error: true })}
            tableBadge={({ id }) => {
              const status = tables.find((table) => table.id === id)?.status;
              const badge = status && badges[status];
              return badge ?? badges.unavailable;
            }}
            tables={tables.filter(({ x, y }) => x !== undefined && y !== undefined).map((table) => ({
              id: table.id, hallId: hall.id, tableNumber: table.number, capacity: table.capacity, label: tableName(table),
              x: table.x ?? null, y: table.y ?? null,
            }))}
            onSelectTable={(id) => {
              const table = tables.find((candidate) => candidate.id === id);
              if (table) choose(table);
            }}
            onCreateTable={() => undefined} onMoveTable={() => undefined} />
        </div>
      </div>
    </section>
  );
}
