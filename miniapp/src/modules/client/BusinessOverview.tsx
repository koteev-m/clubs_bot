import { type ClubProfile } from './onboarding.types';
import { useRemote } from './onboarding.hooks';
import { RemoteState } from './OnboardingShared';
import { OnboardingError } from './onboarding.api';
type Metrics = {
  clubId: string;
  nights: { businessDate: string; bookings: number | null; checkins: number | null }[];
  reports: {
    nightStart: string;
    closedAt: string;
    people: number;
    totalRevenueMinor: string;
  }[];
};
const counter = (value: unknown) =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const money = new Intl.NumberFormat('ru-RU', {
  style: 'currency',
  currency: 'RUB',
});
function formatMinor(value: string) {
  const minor = BigInt(value);
  const absolute = minor < 0n ? -minor : minor;
  const text = money
    .formatToParts(absolute / 100n)
    .map((part) =>
      part.type === 'fraction'
        ? (absolute % 100n).toString().padStart(2, '0')
        : part.value,
    )
    .join('');
  return minor < 0n ? '−' + text : text;
}
export default function BusinessOverview({
  credential,
  profile,
}: {
  credential: string;
  profile: ClubProfile;
}) {
  const remote = useRemote<Metrics>(
    credential,
    '/api/client/clubs/' + profile.id + '/business-overview',
    undefined,
    {
      onSuccess: (view) => {
        if (
          view.clubId !== profile.id ||
          !Array.isArray(view.nights) ||
          !Array.isArray(view.reports) ||
          !view.nights.every(
            (night) =>
              /^\d{4}-\d{2}-\d{2}$/.test(night.businessDate) &&
              ((counter(night.checkins) && counter(night.bookings)) ||
                (night.checkins === null && night.bookings === null)),
          ) ||
          !view.reports.every(
            (report) =>
              [report.nightStart, report.closedAt].every((instant) =>
                Number.isFinite(Date.parse(instant)),
              ) &&
              counter(report.people) &&
              typeof report.totalRevenueMinor === 'string' &&
              /^-?\d+$/.test(report.totalRevenueMinor),
          )
        )
          throw new OnboardingError(503, 'invalid_response');
      },
    },
  );
  if (!remote.data) return <RemoteState {...remote} />;
  const { reports, nights } = remote.data;
  const formatTime = new Intl.DateTimeFormat('ru-RU', {
    timeZone: profile.timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  return (
    <>
      {nights.length > 0 && (
        <section className="po-card ob-card">
          <h2>Брони и посещения за последние ночи</h2>
          {nights.map((night) => (
            <div key={night.businessDate}>
              <h3>{night.businessDate.split('-').reverse().join('.')}</h3>
              {night.bookings === null ? (
                <p>Данные о бронях и посещениях недоступны.</p>
              ) : (
                <>
                  <p>Подтверждённые брони и посадки: {night.bookings}</p>
                  <p>Отметки о входе: {night.checkins}</p>
                </>
              )}
            </div>
          ))}
        </section>
      )}
      {reports.length > 0 && (
        <section className="po-card ob-card">
          <h2>Отчёты закрытых смен</h2>
          {reports.map((report) => (
            <div key={report.nightStart}>
              <h3>
                Смена с {formatTime.format(Date.parse(report.nightStart))}
              </h3>
              <p>Закрыта {formatTime.format(Date.parse(report.closedAt))}</p>
              <p>Посетителей: {report.people}</p>
              <p>Итоговая выручка: {formatMinor(report.totalRevenueMinor)}</p>
            </div>
          ))}
        </section>
      )}
    </>
  );
}
