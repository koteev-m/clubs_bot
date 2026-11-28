import { Grid2X2, Map } from 'lucide-react';
import { tableName } from './clubNightDesk.types';
import type { Hall, Table, TableStatus } from './clubNightDesk.types';

export const tableStatus: Record<TableStatus, string> = {
  free: 'Свободен',
  reserved: 'Бронь',
  seated: 'Занят',
  stopped: 'Стоп продаж',
  unavailable: 'Недоступен',
};

const filters = [
  [undefined, 'Все'],
  ['free', 'Свободные'],
  ['reserved', 'Брони'],
  ['seated', 'Занятые'],
] as const;

export default function ClubNightTableGrid({
  halls, hallId, setHallId, status, setStatus, map, setMap, picking, eligible, choose,
}: {
  halls: Hall[];
  hallId: string;
  setHallId: (id: string) => void;
  status?: TableStatus;
  setStatus: (status?: TableStatus) => void;
  map: boolean;
  setMap: (map: boolean) => void;
  picking: boolean;
  eligible: (table: Table) => boolean;
  choose: (hall: Hall, table: Table) => void;
}) {
  const visibleHalls = halls.filter((hall) => !hallId || hall.id === hallId);
  const visibleTables = (hall: Hall) => hall.tables.filter(
    (table) => eligible(table) && (picking || !status || table.status === status),
  );
  const hasTables = visibleHalls.some((hall) => visibleTables(hall).length > 0);
  return (
    <>
      <div className="night-work-section-title">
        <h3>Столы</h3>
        <button type="button" className="ob-button night-grid-map-toggle" onClick={() => setMap(!map)}
          aria-pressed={map}>
          {map ? <Grid2X2 size={16} aria-hidden="true" /> : <Map size={16} aria-hidden="true" />}
          {map ? 'Сетка' : 'Схема'}
        </button>
      </div>
      {halls.length > 1 && (
        <label className="night-work-select night-grid-hall-select">
          <span className="sr-only">Зал</span>
          <select value={hallId} onChange={(event) => setHallId(event.target.value)}>
            <option value="">Все залы</option>
            {halls.map((hall) => <option key={hall.id} value={hall.id}>{hall.name}</option>)}
          </select>
        </label>
      )}
      {!picking && (
        <div className="night-grid-filters" aria-label="Статус стола" role="group">
          {filters.map(([value, label]) => (
            <button key={label} type="button" className="ob-button" aria-pressed={status === value}
              onClick={() => setStatus(value)} aria-label={value === 'reserved' ? 'Столы с бронью' : undefined}>
              {label}
            </button>
          ))}
        </div>
      )}
      {!hasTables && <p className="po-muted">{halls.length ? 'Подходящих столов нет.' : 'Для этой ночи нет доступных залов.'}</p>}
      {visibleHalls.map((hall) => {
        const tables = visibleTables(hall);
        if (!tables.length) return null;
        const positioned = map ? tables.filter(({ x, y }) => x !== undefined && y !== undefined) : [];
        const grid = map ? tables.filter(({ x, y }) => x === undefined || y === undefined) : tables;
        return (
          <section key={hall.id} className="night-grid-hall" aria-label={hall.name}>
            <h4>{hall.name}</h4>
            {positioned.length > 0 && (
              <div className="night-work-map" aria-label={'Схема · ' + hall.name}>
                {positioned.map((table) => (
                  <button key={table.id} type="button" data-status={table.status} title={tableName(table)}
                    style={{ left: `clamp(22px, ${(table.x ?? 0) * 100}%, calc(100% - 22px))`, top: `clamp(22px, ${(table.y ?? 0) * 100}%, calc(100% - 22px))` }}
                    onClick={() => choose(hall, table)} aria-label={tableName(table) + ' · ' + tableStatus[table.status]}>
                    {table.number}
                  </button>
                ))}
              </div>
            )}
            {grid.length > 0 && <ul className="night-grid-tiles">{grid.map((table) => (
              <li key={table.id}>
                <button type="button" className="night-grid-tile" data-status={table.status} aria-label={tableName(table)}
                  aria-describedby={'table-status-' + table.id} onClick={() => choose(hall, table)}>
                  <strong>{tableName(table).replace(/^Стол\s+(?=\d+$)/i, '')}</strong>
                  <span id={'table-status-' + table.id}>{tableStatus[table.status]}</span>
                </button>
              </li>
            ))}</ul>}
          </section>
        );
      })}
    </>
  );
}
