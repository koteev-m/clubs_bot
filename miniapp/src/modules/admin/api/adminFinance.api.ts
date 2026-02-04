import { http } from '../../../shared/api/http';
import { normalizeAdminError } from './admin.api';

export type AdminShiftReportBracelet = {
  braceletTypeId: number;
  count: number;
};

export type AdminShiftReportRevenueEntry = {
  id: number;
  articleId?: number | null;
  name: string;
  groupId: number;
  amountMinor: number;
  includeInTotal: boolean;
  showSeparately: boolean;
  orderIndex: number;
};

export type AdminShiftReport = {
  id: number;
  clubId: number;
  nightStartUtc: string;
  status: string;
  peopleWomen: number;
  peopleMen: number;
  peopleRejected: number;
  comment?: string | null;
  closedAt?: string | null;
  closedBy?: number | null;
  createdAt: string;
  updatedAt: string;
  bracelets: AdminShiftReportBracelet[];
  revenueEntries: AdminShiftReportRevenueEntry[];
};

export type AdminFinanceBraceletType = {
  id: number;
  name: string;
  enabled: boolean;
  orderIndex: number;
};

export type AdminFinanceRevenueGroup = {
  id: number;
  name: string;
  enabled: boolean;
  orderIndex: number;
};

export type AdminFinanceRevenueArticle = {
  id: number;
  groupId: number;
  name: string;
  enabled: boolean;
  includeInTotal: boolean;
  showSeparately: boolean;
  orderIndex: number;
};

export type AdminFinanceTemplate = {
  clubId: number;
  createdAt: string;
  updatedAt: string;
  bracelets: AdminFinanceBraceletType[];
  revenueGroups: AdminFinanceRevenueGroup[];
  revenueArticles: AdminFinanceRevenueArticle[];
};

export type AdminShiftReportTotals = {
  totalAmountMinor: number;
  groups: Array<{ groupId: number; groupName?: string | null; amountMinor: number }>;
};

export type AdminShiftReportNonTotalIndicators = {
  all: AdminShiftReportRevenueEntry[];
  showSeparately: AdminShiftReportRevenueEntry[];
};

export type AdminShiftReportDepositHints = {
  sumDepositsForNight: number;
  allocationSummaryForNight: Record<string, number>;
};

export type AdminShiftReportDetails = {
  report: AdminShiftReport;
  template: AdminFinanceTemplate;
  totals: AdminShiftReportTotals;
  nonTotalIndicators: AdminShiftReportNonTotalIndicators;
  depositHints: AdminShiftReportDepositHints;
};

export type AdminShiftReportUpdatePayload = {
  peopleWomen: number;
  peopleMen: number;
  peopleRejected: number;
  comment?: string | null;
  bracelets: Array<{ braceletTypeId: number; count: number }>;
  revenueEntries: Array<{
    articleId?: number | null;
    name?: string | null;
    groupId?: number | null;
    amountMinor: number;
    includeInTotal?: boolean | null;
    showSeparately?: boolean | null;
    orderIndex?: number | null;
  }>;
};

export type AdminShiftReportCloseResponse = {
  report: AdminShiftReport;
  totals: AdminShiftReportTotals;
};

export const getShiftReport = async (
  clubId: number,
  nightStartUtc: string,
  signal?: AbortSignal,
): Promise<AdminShiftReportDetails> => {
  try {
    const response = await http.get<AdminShiftReportDetails>(
      `/api/admin/clubs/${clubId}/nights/${encodeURIComponent(nightStartUtc)}/finance/shift`,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updateShiftReport = async (
  clubId: number,
  reportId: number,
  payload: AdminShiftReportUpdatePayload,
  signal?: AbortSignal,
): Promise<AdminShiftReportDetails> => {
  try {
    const response = await http.put<AdminShiftReportDetails>(`/api/admin/clubs/${clubId}/finance/shift/${reportId}`, payload, {
      signal,
    });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const closeShiftReport = async (
  clubId: number,
  reportId: number,
  signal?: AbortSignal,
): Promise<AdminShiftReportCloseResponse> => {
  try {
    const response = await http.post<AdminShiftReportCloseResponse>(
      `/api/admin/clubs/${clubId}/finance/shift/${reportId}/close`,
      {},
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const getFinanceTemplate = async (clubId: number, signal?: AbortSignal): Promise<AdminFinanceTemplate> => {
  try {
    const response = await http.get<AdminFinanceTemplate>(`/api/admin/clubs/${clubId}/finance/template`, { signal });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const createFinanceBraceletType = async (
  clubId: number,
  payload: { name: string; orderIndex?: number | null },
  signal?: AbortSignal,
): Promise<AdminFinanceBraceletType> => {
  try {
    const response = await http.post<AdminFinanceBraceletType>(`/api/admin/clubs/${clubId}/finance/template/bracelets`, payload, {
      signal,
    });
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updateFinanceBraceletType = async (
  clubId: number,
  braceletId: number,
  payload: { name: string },
  signal?: AbortSignal,
): Promise<AdminFinanceBraceletType> => {
  try {
    const response = await http.put<AdminFinanceBraceletType>(
      `/api/admin/clubs/${clubId}/finance/template/bracelets/${braceletId}`,
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const disableFinanceBraceletType = async (clubId: number, braceletId: number, signal?: AbortSignal): Promise<void> => {
  try {
    await http.post(`/api/admin/clubs/${clubId}/finance/template/bracelets/${braceletId}/disable`, {}, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const reorderFinanceBraceletTypes = async (
  clubId: number,
  ids: number[],
  signal?: AbortSignal,
): Promise<void> => {
  try {
    await http.post(`/api/admin/clubs/${clubId}/finance/template/bracelets/reorder`, { ids }, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const createFinanceRevenueGroup = async (
  clubId: number,
  payload: { name: string; orderIndex?: number | null },
  signal?: AbortSignal,
): Promise<AdminFinanceRevenueGroup> => {
  try {
    const response = await http.post<AdminFinanceRevenueGroup>(
      `/api/admin/clubs/${clubId}/finance/template/revenue-groups`,
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updateFinanceRevenueGroup = async (
  clubId: number,
  groupId: number,
  payload: { name: string },
  signal?: AbortSignal,
): Promise<AdminFinanceRevenueGroup> => {
  try {
    const response = await http.put<AdminFinanceRevenueGroup>(
      `/api/admin/clubs/${clubId}/finance/template/revenue-groups/${groupId}`,
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const disableFinanceRevenueGroup = async (clubId: number, groupId: number, signal?: AbortSignal): Promise<void> => {
  try {
    await http.post(`/api/admin/clubs/${clubId}/finance/template/revenue-groups/${groupId}/disable`, {}, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const reorderFinanceRevenueGroups = async (clubId: number, ids: number[], signal?: AbortSignal): Promise<void> => {
  try {
    await http.post(`/api/admin/clubs/${clubId}/finance/template/revenue-groups/reorder`, { ids }, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const createFinanceRevenueArticle = async (
  clubId: number,
  payload: { groupId: number; name: string; includeInTotal?: boolean | null; showSeparately?: boolean | null; orderIndex?: number | null },
  signal?: AbortSignal,
): Promise<AdminFinanceRevenueArticle> => {
  try {
    const response = await http.post<AdminFinanceRevenueArticle>(
      `/api/admin/clubs/${clubId}/finance/template/revenue-articles`,
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const updateFinanceRevenueArticle = async (
  clubId: number,
  articleId: number,
  payload: { groupId: number; name: string; includeInTotal: boolean; showSeparately: boolean },
  signal?: AbortSignal,
): Promise<AdminFinanceRevenueArticle> => {
  try {
    const response = await http.put<AdminFinanceRevenueArticle>(
      `/api/admin/clubs/${clubId}/finance/template/revenue-articles/${articleId}`,
      payload,
      { signal },
    );
    return response.data;
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const disableFinanceRevenueArticle = async (
  clubId: number,
  articleId: number,
  signal?: AbortSignal,
): Promise<void> => {
  try {
    await http.post(`/api/admin/clubs/${clubId}/finance/template/revenue-articles/${articleId}/disable`, {}, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};

export const reorderFinanceRevenueArticles = async (
  clubId: number,
  ids: number[],
  signal?: AbortSignal,
): Promise<void> => {
  try {
    await http.post(`/api/admin/clubs/${clubId}/finance/template/revenue-articles/reorder`, { ids }, { signal });
  } catch (error) {
    throw normalizeAdminError(error);
  }
};
