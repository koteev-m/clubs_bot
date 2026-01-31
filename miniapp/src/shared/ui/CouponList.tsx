import { format } from 'date-fns';
import { ru } from 'date-fns/locale';
import { GuestGamificationCoupon } from '../../modules/guest/api/gamification.api';

const statusLabels: Record<string, string> = {
  AVAILABLE: 'Активен',
  REDEEMED: 'Использован',
  EXPIRED: 'Истёк',
};

const formatDate = (value?: string | null) => {
  if (!value) return '—';
  try {
    return format(new Date(value), 'dd MMM yyyy', { locale: ru });
  } catch {
    return '—';
  }
};

export default function CouponList({ coupons }: { coupons: GuestGamificationCoupon[] }) {
  if (coupons.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-gray-200 bg-white p-4 text-sm text-gray-500">
        Активных купонов пока нет.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {coupons.map((coupon) => {
        const statusLabel = statusLabels[coupon.status] ?? coupon.status;
        const isActive = coupon.status === 'AVAILABLE';
        return (
          <div key={coupon.id} className="rounded-lg border border-gray-200 bg-white p-4 space-y-2">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-sm font-medium text-gray-900">{coupon.prize.titleRu}</div>
                <div className="text-xs text-gray-500">Купон #{coupon.id}</div>
              </div>
              <span
                className={`px-2 py-1 text-xs rounded ${
                  isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                }`}
              >
                {statusLabel}
              </span>
            </div>
            <div className="text-xs text-gray-500">Выдан: {formatDate(coupon.issuedAt)}</div>
            <div className="text-xs text-gray-500">Действует до: {formatDate(coupon.expiresAt)}</div>
            <button
              className="mt-2 rounded bg-blue-600 px-3 py-2 text-xs text-white disabled:opacity-60"
              type="button"
              disabled={!isActive}
            >
              Показать купон
            </button>
          </div>
        );
      })}
    </div>
  );
}
