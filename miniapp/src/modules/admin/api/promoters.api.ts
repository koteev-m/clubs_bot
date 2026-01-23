import { http } from '../../../shared/api/http';
import { normalizeAdminError } from './admin.api';

export type AdminPromoterQuota = {
  tableId: number;
  quota: number;
  held: number;
  expiresAt: string;
};

export type AdminPromoter = {
  promoterId: number;
  telegramUserId: number;
  username?: string | null;
  displayName?: string | null;
  accessEnabled: boolean;
  quotas: AdminPromoterQuota[];
};

type AdminPromotersResponse = {
  promoters: AdminPromoter[];
};

type PromoterAccessResponse = {
  enabled: boolean;
};

type PromoterQuotaResponse = {
  quota: AdminPromoterQuota & { clubId: number; promoterId: number };
};

export const listPromoters = async (clubId: number, signal?: AbortSignal): Promise<AdminPromoter[]> => {
  try {
    const response = await http.get<AdminPromotersResponse>('/api/admin/promoters', {
      params: { clubId },
      signal,
    });
    return response.data.promoters;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updatePromoterAccess = async (
  promoterId: number,
  payload: { clubId: number; enabled: boolean },
  signal?: AbortSignal,
): Promise<boolean> => {
  try {
    const response = await http.post<PromoterAccessResponse>(
      `/api/admin/promoters/${promoterId}/access`,
      payload,
      { signal },
    );
    return response.data.enabled;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const createPromoterQuota = async (
  payload: { clubId: number; promoterId: number; tableId: number; quota: number; expiresAt: string },
  signal?: AbortSignal,
): Promise<AdminPromoterQuota> => {
  try {
    const response = await http.post<PromoterQuotaResponse>('/api/admin/quotas', payload, { signal });
    return response.data.quota;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updatePromoterQuota = async (
  payload: { clubId: number; promoterId: number; tableId: number; quota: number; expiresAt: string },
  signal?: AbortSignal,
): Promise<AdminPromoterQuota> => {
  try {
    const response = await http.put<PromoterQuotaResponse>('/api/admin/quotas', payload, { signal });
    return response.data.quota;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};
