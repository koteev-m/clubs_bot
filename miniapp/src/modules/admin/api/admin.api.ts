import axios from 'axios';
import { http } from '../../../shared/api/http';

export type AdminClub = {
  id: number;
  name: string;
  city: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
};

export type AdminHall = {
  id: number;
  clubId: number;
  name: string;
  isActive: boolean;
  layoutRevision: number;
  geometryFingerprint: string;
  createdAt: string;
  updatedAt: string;
};

type ApiErrorPayload = {
  code: string;
  message?: string | null;
  status?: number | null;
  requestId?: string | null;
  details?: Record<string, string> | null;
};

export class AdminApiError extends Error {
  status?: number;
  code?: string;
  details?: Record<string, string>;
  isAbort: boolean;

  constructor(
    message: string,
    options?: {
      status?: number;
      code?: string;
      details?: Record<string, string>;
      isAbort?: boolean;
    },
  ) {
    super(message);
    this.name = 'AdminApiError';
    this.status = options?.status;
    this.code = options?.code;
    this.details = options?.details;
    this.isAbort = options?.isAbort ?? false;
  }
}

export const isAbortError = (error: unknown): boolean => {
  if (error instanceof AdminApiError) return error.isAbort;
  if (axios.isAxiosError(error)) {
    return error.code === 'ERR_CANCELED' || error.name === 'CanceledError';
  }
  if (error instanceof DOMException) {
    return error.name === 'AbortError';
  }
  return false;
};

export const normalizeAdminError = (error: unknown): AdminApiError => {
  if (error instanceof AdminApiError) return error;
  if (isAbortError(error)) {
    return new AdminApiError('Запрос отменен', { isAbort: true });
  }
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as ApiErrorPayload | undefined;
    return new AdminApiError(payload?.message ?? 'Ошибка запроса', {
      status: payload?.status ?? error.response?.status,
      code: payload?.code,
      details: payload?.details ?? undefined,
    });
  }
  if (error instanceof Error) {
    return new AdminApiError(error.message);
  }
  return new AdminApiError('Неизвестная ошибка');
};

export const listClubs = async (signal?: AbortSignal): Promise<AdminClub[]> => {
  try {
    const response = await http.get<AdminClub[]>('/api/admin/clubs', { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const createClub = async (
  payload: { name: string; city: string; isActive: boolean },
  signal?: AbortSignal,
): Promise<AdminClub> => {
  try {
    const response = await http.post<AdminClub>('/api/admin/clubs', payload, { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updateClub = async (
  clubId: number,
  payload: { name?: string; city?: string; isActive?: boolean },
  signal?: AbortSignal,
): Promise<AdminClub> => {
  try {
    const response = await http.patch<AdminClub>(`/api/admin/clubs/${clubId}`, payload, { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const deleteClub = async (clubId: number, signal?: AbortSignal): Promise<void> => {
  try {
    await http.delete(`/api/admin/clubs/${clubId}`, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const listHalls = async (clubId: number, signal?: AbortSignal): Promise<AdminHall[]> => {
  try {
    const response = await http.get<AdminHall[]>(`/api/admin/clubs/${clubId}/halls`, { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const createHall = async (
  clubId: number,
  payload: { name: string; geometryJson: string; isActive: boolean },
  signal?: AbortSignal,
): Promise<AdminHall> => {
  try {
    const response = await http.post<AdminHall>(`/api/admin/clubs/${clubId}/halls`, payload, { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updateHall = async (
  hallId: number,
  payload: { name?: string; geometryJson?: string },
  signal?: AbortSignal,
): Promise<AdminHall> => {
  try {
    const response = await http.patch<AdminHall>(`/api/admin/halls/${hallId}`, payload, { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const deleteHall = async (hallId: number, signal?: AbortSignal): Promise<void> => {
  try {
    await http.delete(`/api/admin/halls/${hallId}`, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const makeHallActive = async (hallId: number, signal?: AbortSignal): Promise<AdminHall> => {
  try {
    const response = await http.post<AdminHall>(`/api/admin/halls/${hallId}/make-active`, {}, { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};
