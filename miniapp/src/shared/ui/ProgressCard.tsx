import { GuestGamificationReward } from '../../modules/guest/api/gamification.api';

const metricLabels: Record<string, string> = {
  VISITS: 'Визиты',
  EARLY_VISITS: 'Ранние визиты',
  TABLE_NIGHTS: 'Ночи за столом',
};

const getMetricLabel = (metricType: string) => metricLabels[metricType] ?? 'Прогресс';

const formatWindow = (windowDays: number) => {
  if (!windowDays || windowDays <= 0) return '';
  return `за ${windowDays} дн.`;
};

export default function ProgressCard({ rewards }: { rewards: GuestGamificationReward[] }) {
  if (rewards.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-gray-200 bg-white p-4 text-sm text-gray-500">
        Пока нет активных уровней наград.
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4 space-y-3">
      <div className="text-base font-semibold">Следующая награда</div>
      <div className="space-y-3">
        {rewards.map((reward) => {
          const progress = reward.threshold > 0 ? Math.min(100, Math.round((reward.current / reward.threshold) * 100)) : 0;
          return (
            <div key={`${reward.metricType}-${reward.prize.id}`} className="space-y-2">
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-medium text-gray-900">{reward.prize.titleRu}</div>
                  <div className="text-xs text-gray-500">
                    {getMetricLabel(reward.metricType)} {formatWindow(reward.windowDays)}
                  </div>
                </div>
                <div className="text-xs text-gray-500">Осталось: {reward.remaining}</div>
              </div>
              <div className="h-2 rounded-full bg-gray-100">
                <div className="h-2 rounded-full bg-blue-500" style={{ width: `${progress}%` }} />
              </div>
              <div className="text-xs text-gray-500">
                Прогресс: {reward.current} из {reward.threshold}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
