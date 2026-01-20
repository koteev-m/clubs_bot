import { useEffect, useMemo, useState } from 'react';
import { getPromoterStats, listPromoterClubs, normalizePromoterError, PromoterClub, PromoterStats } from '../api/promoter.api';
import { useUiStore } from '../../../shared/store/ui';

type OverviewScreenProps = {
  onForbidden: () => void;
};

export default function OverviewScreen({ onForbidden }: OverviewScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [clubs, setClubs] = useState<PromoterClub[]>([]);
  const [stats, setStats] = useState<PromoterStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    const load = async () => {
      try {
        const [clubsData, statsData] = await Promise.all([
          listPromoterClubs(controller.signal),
          getPromoterStats(controller.signal),
        ]);
        setClubs(clubsData);
        setStats(statsData);
      } catch (error) {
        const normalized = normalizePromoterError(error);
        if (normalized.status === 401 || normalized.status === 403) {
          onForbidden();
          return;
        }
        addToast(normalized.message);
      } finally {
        setLoading(false);
      }
    };
    load();
    return () => controller.abort();
  }, [addToast, onForbidden]);

  const conversionLabel = useMemo(() => {
    if (!stats) return '0%';
    return `${Math.round(stats.conversion * 100)}%`;
  }, [stats]);

  return (
    <div className="px-4 py-6">
      <h2 className="text-lg font-semibold text-gray-900">Обзор</h2>
      {loading ? (
        <div className="mt-4 text-sm text-gray-500">Загрузка...</div>
      ) : (
        <>
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            <div className="rounded-lg bg-white p-4 shadow-sm">
              <div className="text-xs text-gray-500">Добавлено гостей</div>
              <div className="text-xl font-semibold text-gray-900">{stats?.totalAdded ?? 0}</div>
            </div>
            <div className="rounded-lg bg-white p-4 shadow-sm">
              <div className="text-xs text-gray-500">Дошли</div>
              <div className="text-xl font-semibold text-gray-900">{stats?.totalArrived ?? 0}</div>
            </div>
            <div className="rounded-lg bg-white p-4 shadow-sm">
              <div className="text-xs text-gray-500">Конверсия</div>
              <div className="text-xl font-semibold text-gray-900">{conversionLabel}</div>
            </div>
          </div>
          <div className="mt-6 rounded-lg bg-white p-4 shadow-sm">
            <div className="text-sm font-semibold text-gray-900">Клубы</div>
            {clubs.length === 0 ? (
              <div className="mt-2 text-sm text-gray-500">Клубы не назначены.</div>
            ) : (
              <ul className="mt-2 space-y-2 text-sm text-gray-700">
                {clubs.map((club) => (
                  <li key={club.id} className="flex items-center justify-between">
                    <span>{club.name}</span>
                    <span className="text-xs text-gray-400">{club.city}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </div>
  );
}
