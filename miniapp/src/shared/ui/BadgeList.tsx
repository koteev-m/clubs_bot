import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import { GuestGamificationBadge } from '../../modules/guest/api/gamification.api';

const formatEarnedAt = (value?: string | null) => {
  if (!value) return '';
  try {
    return format(new Date(value), 'dd MMM yyyy', { locale: ru });
  } catch {
    return '';
  }
};

export default function BadgeList({ badges }: { badges: GuestGamificationBadge[] }) {
  if (badges.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-gray-200 bg-white p-4 text-sm text-gray-500">
        Бейджей пока нет.
      </div>
    );
  }

  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {badges.map((badge) => {
        const earned = Boolean(badge.earnedAt);
        const earnedText = formatEarnedAt(badge.earnedAt) || '—';
        return (
          <div
            key={badge.code}
            className={`rounded-lg border p-3 space-y-1 ${
              earned ? 'border-green-200 bg-green-50' : 'border-gray-200 bg-white'
            }`}
          >
            <div className="flex items-center gap-2">
              <div
                className={`flex h-8 w-8 items-center justify-center rounded-full text-sm font-semibold ${
                  earned ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                }`}
              >
                {badge.icon ?? '★'}
              </div>
              <div className="text-sm font-medium text-gray-900">{badge.nameRu}</div>
            </div>
            <div className="text-xs text-gray-500">
              {earned ? `Получен ${earnedText}` : 'Ещё не получен'}
            </div>
          </div>
        );
      })}
    </div>
  );
}
