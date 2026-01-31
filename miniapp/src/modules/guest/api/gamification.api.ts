import type { AxiosRequestConfig } from 'axios';
import { http } from '../../../shared/api/http';

export interface GuestGamificationPrize {
  id: number;
  code: string;
  titleRu: string;
  description?: string | null;
  terms?: string | null;
}

export interface GuestGamificationReward {
  metricType: string;
  threshold: number;
  windowDays: number;
  current: number;
  remaining: number;
  prize: GuestGamificationPrize;
}

export interface GuestGamificationBadge {
  code: string;
  nameRu: string;
  icon?: string | null;
  earnedAt?: string | null;
}

export interface GuestGamificationCoupon {
  id: number;
  status: string;
  issuedAt: string;
  expiresAt?: string | null;
  prize: GuestGamificationPrize;
}

export interface GuestGamificationTotals {
  visitsAllTime: number;
  visitsInWindow: number;
  earlyInWindow: number;
  tableNightsInWindow: number;
}

export interface GuestGamificationResponse {
  clubId: number;
  nowUtc: string;
  totals: GuestGamificationTotals;
  nextRewards: GuestGamificationReward[];
  badges: GuestGamificationBadge[];
  coupons: GuestGamificationCoupon[];
}

export function getClubGamification(clubId: number, config?: AxiosRequestConfig) {
  return http.get<GuestGamificationResponse>(`/api/me/clubs/${clubId}/gamification`, config);
}
