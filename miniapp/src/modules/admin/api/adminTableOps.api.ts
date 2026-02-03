import { http } from '../../../shared/api/http';
import { AdminApiError, normalizeAdminError } from './admin.api';

export type AdminDepositAllocation = {
  categoryCode: string;
  amountMinor: number;
};

export type AdminTableDeposit = {
  id: number;
  amountMinor: number;
  guestUserId?: number | null;
  allocations: AdminDepositAllocation[];
};

export type AdminNightTable = {
  tableId: number;
  label: string;
  tableNumber: number;
  isOccupied: boolean;
  activeSessionId?: number | null;
  activeDeposit?: AdminTableDeposit | null;
};

export type AdminSeatTablePayload = {
  mode: 'WITH_QR' | 'NO_QR';
  guestPassQr?: string;
  depositAmount: number;
  allocations: Array<{ categoryCode: string; amount: number }>;
};

export type AdminSeatTableResponse = {
  sessionId: number;
  depositId: number;
  table: AdminNightTable;
};

export type AdminFreeTableResponse = {
  closedSessionId: number;
  table: AdminNightTable;
};

export type AdminUpdateDepositPayload = {
  amount: number;
  allocations: Array<{ categoryCode: string; amount: number }>;
  reason: string;
};

export type AdminUpdateDepositResponse = {
  deposit: AdminTableDeposit;
};

export const getTablesForNight = async (
  clubId: number,
  nightKey: string,
  signal?: AbortSignal,
): Promise<AdminNightTable[]> => {
  try {
    const response = await http.get<AdminNightTable[]>(
      `/api/admin/clubs/${clubId}/nights/${encodeURIComponent(nightKey)}/tables`,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const seatTable = async (
  clubId: number,
  nightKey: string,
  tableId: number,
  payload: AdminSeatTablePayload,
  signal?: AbortSignal,
): Promise<AdminSeatTableResponse> => {
  try {
    const response = await http.post<AdminSeatTableResponse>(
      `/api/admin/clubs/${clubId}/nights/${encodeURIComponent(nightKey)}/tables/${tableId}/seat`,
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const freeTable = async (
  clubId: number,
  nightKey: string,
  tableId: number,
  signal?: AbortSignal,
): Promise<AdminFreeTableResponse> => {
  try {
    const response = await http.post<AdminFreeTableResponse>(
      `/api/admin/clubs/${clubId}/nights/${encodeURIComponent(nightKey)}/tables/${tableId}/free`,
      {},
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updateDeposit = async (
  clubId: number,
  nightKey: string,
  depositId: number,
  payload: AdminUpdateDepositPayload,
  signal?: AbortSignal,
): Promise<AdminUpdateDepositResponse> => {
  try {
    const response = await http.put<AdminUpdateDepositResponse>(
      `/api/admin/clubs/${clubId}/nights/${encodeURIComponent(nightKey)}/deposits/${depositId}`,
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const isAdminTableOpsUnauthorized = (error: unknown): boolean => {
  if (error instanceof AdminApiError) {
    return error.status === 401;
  }
  return false;
};
