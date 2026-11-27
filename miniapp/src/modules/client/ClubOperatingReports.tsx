import { useState } from 'react';
import { OnboardingError } from './onboarding.api';
import { useRemote } from './onboarding.hooks';
import { RemoteState, Shell } from './OnboardingShared';

type Query = { period: string; from?: string; to?: string };
type Metrics = {
  bookings: number;
  seatedBookings: number;
  cancelledBookings: number;
  noShowBookings: number;
  entrants: number;
  denials: number;
  usedTables: number;
  occupiedTables: number;
  sessions: number;
  sessionsWithKnownCount: number;
  knownSeatedGuests: number | null;
};
type Report = {
  clubId: string;
  configuredTables: number;
  configuredPlaces: number;
  nights: { businessDate: string; preliminary: boolean; metrics: Metrics | null }[];
};

export default function ClubOperatingReports({ id, credential, back }: { id: string; credential: string; back: () => void }) {
  const [period, setPeriod] = useState('current');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [query, setQuery] = useState<Query>({ period: 'current' });
  const remote = useRemote<Report>(credential, '/api/client/clubs/' + id + '/operations/reports', query, {
    onSuccess: (data) => {
      if (data.clubId !== id || !Array.isArray(data.nights)) throw new OnboardingError(502, 'invalid_response');
    },
  });
  return (
    <Shell title="Операционные показатели" back={back} backLabel="Назад к клубу">
      <form className="po-card ob-card cm-preparation" onSubmit={(event) => {
        event.preventDefault();
        setQuery(period === 'range' ? { period, from, to } : { period });
      }}>
        <label>Период <select value={period} onChange={(event) => setPeriod(event.target.value)}>
          <option value="current">Текущая ночь</option>
          <option value="previous">Предыдущая ночь</option>
          <option value="week">Последние 7 дней</option>
          <option value="month">Текущий месяц</option>
          <option value="range">Выбранный диапазон</option>
        </select></label>
        {period === 'range' && <>
          <label>Начальная дата <input type="date" required value={from} onChange={(event) => setFrom(event.target.value)} /></label>
          <label>Конечная дата <input type="date" required value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} /></label>
        </>}
        <button className="po-primary" type="submit" disabled={remote.loading}>Показать</button>
      </form>
      <RemoteState {...remote} />
      {remote.data && (
        <>
          <section className="po-card ob-card">
            <h2>Текущая конфигурация</h2>
            <p>Столов {remote.data.configuredTables} · мест за столами {remote.data.configuredPlaces}</p>
          </section>
          {remote.data.nights.length === 0 && <p>За выбранный период нет подтверждённого расписания ночей.</p>}
          {remote.data.nights.map(({ businessDate, preliminary, metrics }) => (
            <section className="po-card ob-card" key={businessDate}>
              <h2>{businessDate.split('-').reverse().join('.')}</h2>
              {preliminary && <p>Предварительные данные</p>}
              {metrics ? (
                <>
                  <p>Брони {metrics.bookings} · посадки по статусу брони {metrics.seatedBookings} · отмены {metrics.cancelledBookings} · неявки {metrics.noShowBookings}</p>
                  <p>Входы {metrics.entrants} · отказы {metrics.denials}</p>
                  <p>Использовано столов {metrics.usedTables} · активных посадок {metrics.occupiedTables}</p>
                  <p>{metrics.knownSeatedGuests == null ? 'Фактическое число гостей неизвестно.' :
                    'Фактически село ' + metrics.knownSeatedGuests + ' гостей по ' + metrics.sessionsWithKnownCount + ' из ' + metrics.sessions + ' посадок.'}</p>
                </>
              ) : <p>Связь ночи с операционным источником не подтверждена.</p>}
            </section>
          ))}
        </>
      )}
    </Shell>
  );
}
