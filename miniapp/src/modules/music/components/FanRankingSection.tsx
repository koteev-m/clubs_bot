import React from 'react';
import { useFanRanking } from '../hooks/useFanRanking';

const DistributionBar: React.FC<{ label: string; value: number; max: number }> = ({ label, value, max }) => {
  const width = max > 0 ? Math.round((value / max) * 100) : 0;
  return (
    <div>
      <div className="flex justify-between text-xs">
        <span>{label}</span>
        <span>{value}</span>
      </div>
      <div className="h-2 bg-gray-100 rounded overflow-hidden">
        <div className="h-full bg-emerald-500" style={{ width: `${width}%` }} />
      </div>
    </div>
  );
};

export const FanRankingSection: React.FC<{ clubId?: number; enabled?: boolean }> = ({ clubId, enabled = true }) => {
  const ranking = useFanRanking(clubId, 30, enabled);

  return (
    <section className="mb-6" aria-label="fan-ranking-section">
      <div className="flex items-center justify-between mb-2">
        <h2 className="text-lg">Fan ranking</h2>
        <button type="button" className="text-xs text-blue-600" onClick={() => void ranking.reload()}>
          Обновить
        </button>
      </div>
      {ranking.status === 'loading' && <div className="text-sm text-gray-600">Загрузка рейтинга...</div>}
      {ranking.status === 'unauthorized' && <div className="text-sm text-gray-600">Нужна авторизация</div>}
      {ranking.status === 'forbidden' && <div className="text-sm text-red-600">Рейтинг недоступен</div>}
      {ranking.status === 'error' && (
        <div className="text-sm text-red-600">
          {ranking.errorMessage}
          {ranking.canRetry && (
            <button type="button" className="ml-2 underline" onClick={() => void ranking.reload()}>
              Повторить
            </button>
          )}
        </div>
      )}
      {ranking.status === 'ready' && ranking.data && (
        <div className="border rounded p-3 bg-white text-sm">
          <div className="grid grid-cols-2 gap-2 mb-3">
            <div>Мои голоса: {ranking.data.myStats.votesCast}</div>
            <div>Мои лайки: {ranking.data.myStats.likesGiven}</div>
            <div>Мои очки: {ranking.data.myStats.points}</div>
            <div>Мой ранг: #{ranking.data.myStats.rank}</div>
          </div>
          <div className="text-xs text-gray-500 mb-2">Распределение по фанам без персональных данных</div>
          <div className="space-y-2">
            <DistributionBar label="P50" value={ranking.data.distribution.p50} max={ranking.data.distribution.p99} />
            <DistributionBar label="P90" value={ranking.data.distribution.p90} max={ranking.data.distribution.p99} />
            <DistributionBar label="P99" value={ranking.data.distribution.p99} max={ranking.data.distribution.p99} />
          </div>
        </div>
      )}
    </section>
  );
};
