import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { useUiStore } from '../../../shared/store/ui';
import { http } from '../../../shared/api/http';
import { AdminApiError, AdminClub, listClubs } from '../api/admin.api';
import {
  AdminFinanceRevenueGroup,
  AdminShiftReportDetails,
  AdminShiftReportUpdatePayload,
  closeShiftReport,
  createFinanceBraceletType,
  createFinanceRevenueArticle,
  disableFinanceBraceletType,
  updateShiftReport,
} from '../api/adminFinance.api';
import { useShiftReport } from '../hooks/useShiftReport';
import { NightDto } from '../../../shared/types';
import AuthorizationRequired from '../../../shared/ui/AuthorizationRequired';
import { isRequestCanceled } from '../../../shared/api/error';

type FinanceShiftScreenProps = {
  clubId: number | null;
  onSelectClub: (clubId: number | null) => void;
  onForbidden: () => void;
};

type BraceletRow = {
  key: string;
  braceletTypeId?: number;
  name: string;
  count: string;
  enabled: boolean;
  hasExisting: boolean;
  savedToTemplate: boolean;
  isCustom: boolean;
};

type RevenueEntryRow = {
  key: string;
  entryId?: number;
  articleId?: number | null;
  name: string;
  groupId: number | null;
  amount: string;
  includeInTotal: boolean;
  showSeparately: boolean;
  orderIndex?: number;
  hasExisting: boolean;
  isCustom: boolean;
  isTemplateArticle: boolean;
};

const createKey = () => Math.random().toString(36).slice(2);

const parseNonNegativeInt = (value: string) => {
  const trimmed = value.trim();
  if (!trimmed) return { value: 0 };
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || parsed < 0 || !Number.isInteger(parsed)) {
    return { value: null, error: 'Введите целое неотрицательное число' };
  }
  return { value: parsed };
};

const parseNonNegativeAmount = (value: string) => {
  const trimmed = value.trim();
  if (!trimmed) return { value: 0 };
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || parsed < 0 || !Number.isInteger(parsed)) {
    return { value: null, error: 'Введите сумму неотрицательным числом' };
  }
  return { value: parsed };
};

const sortByOrderIndex = <T extends { orderIndex: number }>(items: T[]) => {
  return [...items].sort((a, b) => a.orderIndex - b.orderIndex);
};

const buildBraceletRows = (details: AdminShiftReportDetails): BraceletRow[] => {
  const reportMap = new Map(details.report.bracelets.map((item) => [item.braceletTypeId, item.count]));
  const templateBracelets = sortByOrderIndex(details.template.bracelets);
  const enabledBracelets = templateBracelets.filter((item) => item.enabled);
  const rows: BraceletRow[] = enabledBracelets.map((item) => ({
    key: `bracelet-${item.id}`,
    braceletTypeId: item.id,
    name: item.name,
    count: String(reportMap.get(item.id) ?? 0),
    enabled: true,
    hasExisting: reportMap.has(item.id),
    savedToTemplate: true,
    isCustom: false,
  }));
  details.report.bracelets.forEach((item) => {
    if (enabledBracelets.some((bracelet) => bracelet.id === item.braceletTypeId)) return;
    const templateMatch = templateBracelets.find((bracelet) => bracelet.id === item.braceletTypeId);
    rows.push({
      key: `bracelet-${item.braceletTypeId}`,
      braceletTypeId: item.braceletTypeId,
      name: templateMatch?.name ?? `Браслет #${item.braceletTypeId}`,
      count: String(item.count),
      enabled: false,
      hasExisting: true,
      savedToTemplate: Boolean(templateMatch),
      isCustom: !templateMatch,
    });
  });
  return rows;
};

const buildRevenueRows = (details: AdminShiftReportDetails): RevenueEntryRow[] => {
  const reportEntries = details.report.revenueEntries;
  const reportByArticle = new Map(reportEntries.filter((item) => item.articleId).map((item) => [item.articleId!, item]));
  const templateArticles = sortByOrderIndex(details.template.revenueArticles);
  const enabledArticles = templateArticles.filter((item) => item.enabled);
  const rows: RevenueEntryRow[] = enabledArticles.map((article) => {
    const entry = reportByArticle.get(article.id);
    return {
      key: `article-${article.id}`,
      entryId: entry?.id,
      articleId: article.id,
      name: article.name,
      groupId: entry?.groupId ?? article.groupId,
      amount: String(entry?.amountMinor ?? 0),
      includeInTotal: entry?.includeInTotal ?? article.includeInTotal,
      showSeparately: entry?.showSeparately ?? article.showSeparately,
      orderIndex: entry?.orderIndex ?? article.orderIndex,
      hasExisting: Boolean(entry),
      isCustom: false,
      isTemplateArticle: true,
    };
  });
  reportEntries.forEach((entry) => {
    if (entry.articleId && enabledArticles.some((item) => item.id === entry.articleId)) return;
    const templateMatch = entry.articleId ? templateArticles.find((item) => item.id === entry.articleId) : null;
    rows.push({
      key: `entry-${entry.id}`,
      entryId: entry.id,
      articleId: entry.articleId,
      name: entry.name || templateMatch?.name || `Статья #${entry.articleId ?? entry.id}`,
      groupId: entry.groupId,
      amount: String(entry.amountMinor),
      includeInTotal: entry.includeInTotal,
      showSeparately: entry.showSeparately,
      orderIndex: entry.orderIndex,
      hasExisting: true,
      isCustom: !entry.articleId,
      isTemplateArticle: Boolean(entry.articleId),
    });
  });
  return rows;
};

const normalizeGroupLabel = (group?: AdminFinanceRevenueGroup | null) => {
  if (!group) return 'Без группы';
  return group.name || `Группа #${group.id}`;
};

export default function FinanceShiftScreen({ clubId, onSelectClub, onForbidden }: FinanceShiftScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [clubs, setClubs] = useState<AdminClub[]>([]);
  const [clubsStatus, setClubsStatus] = useState<'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'>('idle');
  const [clubsError, setClubsError] = useState('');
  const [clubsCanRetry, setClubsCanRetry] = useState(false);
  const [nights, setNights] = useState<NightDto[]>([]);
  const [selectedNight, setSelectedNight] = useState('');
  const [nightsStatus, setNightsStatus] = useState<'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'>('idle');
  const [nightsError, setNightsError] = useState('');
  const [nightsCanRetry, setNightsCanRetry] = useState(false);
  const [isUnauthorized, setIsUnauthorized] = useState(false);
  const [details, setDetails] = useState<AdminShiftReportDetails | null>(null);
  const [peopleWomen, setPeopleWomen] = useState('0');
  const [peopleMen, setPeopleMen] = useState('0');
  const [peopleRejected, setPeopleRejected] = useState('0');
  const [comment, setComment] = useState('');
  const [bracelets, setBracelets] = useState<BraceletRow[]>([]);
  const [revenueEntries, setRevenueEntries] = useState<RevenueEntryRow[]>([]);
  const [formError, setFormError] = useState('');
  const [isSaving, setIsSaving] = useState(false);
  const [isClosing, setIsClosing] = useState(false);
  const [commentError, setCommentError] = useState('');
  const commentRef = useRef<HTMLTextAreaElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const nightsAbortRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);
  const nightRequestIdRef = useRef(0);

  const { status, data, errorMessage, canRetry, reload } = useShiftReport(clubId ?? undefined, selectedNight, onForbidden);

  useEffect(() => {
    if (status === 'unauthorized') {
      setIsUnauthorized(true);
    }
  }, [status]);

  useEffect(() => {
    if (status === 'ready' && data) {
      setDetails(data);
      setPeopleWomen(String(data.report.peopleWomen));
      setPeopleMen(String(data.report.peopleMen));
      setPeopleRejected(String(data.report.peopleRejected));
      setComment(data.report.comment ?? '');
      setBracelets(buildBraceletRows(data));
      setRevenueEntries(buildRevenueRows(data));
      setFormError('');
      setCommentError('');
    }
  }, [data, status]);

  const loadClubs = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const requestId = ++requestIdRef.current;
    setClubsStatus('loading');
    setClubsError('');
    setClubsCanRetry(false);
    try {
      const clubsData = await listClubs(controller.signal);
      if (requestIdRef.current !== requestId) return;
      setClubs(clubsData);
      setClubsStatus('ready');
    } catch (error) {
      if (requestIdRef.current !== requestId) return;
      if (error instanceof AdminApiError) {
        if (error.status === 401) {
          setIsUnauthorized(true);
          setClubsStatus('unauthorized');
          return;
        }
        if (error.status === 403) {
          onForbidden();
          setClubsError('Нет доступа');
          setClubsStatus('error');
          setClubsCanRetry(false);
          return;
        }
        if (!error.status) {
          setClubsError('Не удалось связаться с сервером');
          setClubsCanRetry(true);
          setClubsStatus('error');
          return;
        }
        if (error.status >= 500) {
          setClubsError('Сервис временно недоступен');
          setClubsCanRetry(true);
          setClubsStatus('error');
          return;
        }
        setClubsError(error.message);
        setClubsStatus('error');
        return;
      }
      setClubsError('Не удалось загрузить список клубов');
      setClubsStatus('error');
    }
  }, [onForbidden]);

  useEffect(() => {
    void loadClubs();
    return () => abortRef.current?.abort();
  }, [loadClubs]);

  useEffect(() => {
    setSelectedNight('');
    setNights([]);
    setNightsStatus('idle');
    setNightsError('');
    setNightsCanRetry(false);
    setDetails(null);
  }, [clubId]);

  const loadNights = useCallback(async () => {
    if (!clubId) return;
    nightsAbortRef.current?.abort();
    const controller = new AbortController();
    nightsAbortRef.current = controller;
    const requestId = ++nightRequestIdRef.current;
    setNightsStatus('loading');
    setNightsError('');
    setNightsCanRetry(false);
    try {
      const response = await http.get<NightDto[]>(`/api/clubs/${clubId}/nights?limit=8`, { signal: controller.signal });
      if (nightRequestIdRef.current !== requestId) return;
      setNights(response.data);
      setNightsStatus('ready');
      if (!selectedNight && response.data.length > 0) {
        setSelectedNight(response.data[0].startUtc);
      }
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (nightRequestIdRef.current !== requestId) return;
      if (axios.isAxiosError(error)) {
        const statusCode = error.response?.status;
        if (statusCode === 401) {
          setIsUnauthorized(true);
          setNightsStatus('unauthorized');
          return;
        }
        if (statusCode === 403) {
          onForbidden();
          setNightsError('Нет доступа');
          setNightsStatus('error');
          setNightsCanRetry(false);
          return;
        }
        if (!statusCode) {
          setNightsError('Не удалось связаться с сервером');
          setNightsCanRetry(true);
          setNightsStatus('error');
          return;
        }
        if (statusCode >= 500) {
          setNightsError('Сервис временно недоступен');
          setNightsCanRetry(true);
          setNightsStatus('error');
          return;
        }
      }
      setNightsError('Не удалось загрузить ночи');
      setNightsStatus('error');
    } finally {
      if (nightsAbortRef.current === controller) {
        nightsAbortRef.current = null;
      }
    }
  }, [clubId, onForbidden, selectedNight]);

  useEffect(() => {
    if (!clubId) return;
    void loadNights();
    return () => nightsAbortRef.current?.abort();
  }, [clubId, loadNights]);

  const isClosed = details?.report.status === 'CLOSED';

  const enabledGroups = useMemo(() => {
    return details ? sortByOrderIndex(details.template.revenueGroups).filter((group) => group.enabled) : [];
  }, [details]);

  const templateGroups = useMemo(() => {
    return details ? sortByOrderIndex(details.template.revenueGroups) : [];
  }, [details]);

  const totals = useMemo(() => {
    const totalsByGroup = new Map<number, number>();
    let totalAmount = 0;
    revenueEntries.forEach((entry) => {
      const parsed = parseNonNegativeAmount(entry.amount);
      const value = parsed.value ?? 0;
      if (!Number.isFinite(value) || value < 0) return;
      if (entry.includeInTotal) {
        totalAmount += value;
        if (entry.groupId) {
          totalsByGroup.set(entry.groupId, (totalsByGroup.get(entry.groupId) ?? 0) + value);
        }
      }
    });
    return { totalAmount, totalsByGroup };
  }, [revenueEntries]);

  const indicatorsSum = useMemo(() => {
    return revenueEntries.reduce((acc, entry) => {
      if (!entry.includeInTotal && entry.showSeparately) {
        const parsed = parseNonNegativeAmount(entry.amount);
        const value = parsed.value ?? 0;
        if (Number.isFinite(value) && value >= 0) {
          return acc + value;
        }
      }
      return acc;
    }, 0);
  }, [revenueEntries]);

  const depositMismatch = useMemo(() => {
    const sumDeposits = details?.depositHints.sumDepositsForNight ?? 0;
    const mismatch = Math.abs(sumDeposits - indicatorsSum);
    return { sumDeposits, mismatch };
  }, [details, indicatorsSum]);

  const handleBraceletChange = useCallback((key: string, value: string) => {
    setBracelets((prev) => prev.map((item) => (item.key === key ? { ...item, count: value } : item)));
  }, []);

  const handleAddBracelet = useCallback(() => {
    setBracelets((prev) => [
      ...prev,
      {
        key: `custom-${createKey()}`,
        name: '',
        count: '0',
        enabled: true,
        hasExisting: false,
        savedToTemplate: false,
        isCustom: true,
      },
    ]);
  }, []);

  const handleSaveBraceletToTemplate = useCallback(
    async (row: BraceletRow) => {
      if (!clubId) return;
      const name = row.name.trim();
      if (!name) {
        setFormError('Укажите название браслета для сохранения в шаблон');
        return;
      }
      setFormError('');
      try {
        const orderIndex = details ? details.template.bracelets.length : 0;
        const created = await createFinanceBraceletType(clubId, { name, orderIndex });
        setBracelets((prev) =>
          prev.map((item) =>
            item.key === row.key
              ? {
                  ...item,
                  braceletTypeId: created.id,
                  name: created.name,
                  savedToTemplate: true,
                  isCustom: false,
                }
              : item,
          ),
        );
        addToast('Браслет добавлен в шаблон');
      } catch (error) {
        const normalized = error as AdminApiError;
        if (normalized.status === 401) {
          setIsUnauthorized(true);
          return;
        }
        if (normalized.status === 403) {
          onForbidden();
          return;
        }
        addToast(normalized.message);
      }
    },
    [addToast, clubId, details, onForbidden],
  );

  const handleRemoveBracelet = useCallback((key: string) => {
    setBracelets((prev) => prev.filter((item) => item.key !== key));
  }, []);

  const handleRevenueAmountChange = useCallback((key: string, value: string) => {
    setRevenueEntries((prev) => prev.map((item) => (item.key === key ? { ...item, amount: value } : item)));
  }, []);

  const handleRevenueToggle = useCallback((key: string, patch: Partial<RevenueEntryRow>) => {
    setRevenueEntries((prev) => prev.map((item) => (item.key === key ? { ...item, ...patch } : item)));
  }, []);

  const handleAddRevenueEntry = useCallback(() => {
    const fallbackGroupId = enabledGroups[0]?.id ?? null;
    setRevenueEntries((prev) => [
      ...prev,
      {
        key: `custom-${createKey()}`,
        name: '',
        groupId: fallbackGroupId,
        amount: '0',
        includeInTotal: false,
        showSeparately: false,
        hasExisting: false,
        isCustom: true,
        isTemplateArticle: false,
      },
    ]);
  }, [enabledGroups]);

  const handleSaveRevenueToTemplate = useCallback(
    async (row: RevenueEntryRow) => {
      if (!clubId) return;
      const name = row.name.trim();
      if (!name) {
        setFormError('Укажите название статьи для сохранения в шаблон');
        return;
      }
      if (!row.groupId) {
        setFormError('Выберите группу для статьи');
        return;
      }
      setFormError('');
      try {
        const orderIndex = details ? details.template.revenueArticles.length : 0;
        const created = await createFinanceRevenueArticle(
          clubId,
          { groupId: row.groupId, name, includeInTotal: false, showSeparately: false, orderIndex },
        );
        setRevenueEntries((prev) =>
          prev.map((item) =>
            item.key === row.key
              ? {
                  ...item,
                  articleId: created.id,
                  name: created.name,
                  isCustom: false,
                  isTemplateArticle: true,
                }
              : item,
          ),
        );
        addToast('Статья добавлена в шаблон');
      } catch (error) {
        const normalized = error as AdminApiError;
        if (normalized.status === 401) {
          setIsUnauthorized(true);
          return;
        }
        if (normalized.status === 403) {
          onForbidden();
          return;
        }
        addToast(normalized.message);
      }
    },
    [addToast, clubId, details, onForbidden],
  );

  const handleRemoveRevenueEntry = useCallback((key: string) => {
    setRevenueEntries((prev) => prev.filter((item) => item.key !== key));
  }, []);

  const resolveBraceletPayload = useCallback(
    async () => {
      if (!clubId) return { payload: [], error: 'Выберите клуб' };
      const resolved: AdminShiftReportUpdatePayload['bracelets'] = [];
      const updates: Array<{ key: string; braceletTypeId: number; savedToTemplate: boolean }> = [];
      for (const row of bracelets) {
        const parsed = parseNonNegativeInt(row.count);
        if (parsed.error || parsed.value === null) {
          return { payload: [], error: `Браслеты: ${parsed.error}` };
        }
        let braceletTypeId = row.braceletTypeId;
        if (!braceletTypeId) {
          const name = row.name.trim();
          if (!name) {
            return { payload: [], error: 'Укажите название для добавленного браслета' };
          }
          try {
            const orderIndex = details ? details.template.bracelets.length : 0;
            const created = await createFinanceBraceletType(clubId, { name, orderIndex });
            if (!row.savedToTemplate) {
              await disableFinanceBraceletType(clubId, created.id);
            }
            braceletTypeId = created.id;
            updates.push({ key: row.key, braceletTypeId: created.id, savedToTemplate: row.savedToTemplate });
          } catch (error) {
            const normalized = error as AdminApiError;
            if (normalized.status === 403) {
              onForbidden();
              return { payload: [], error: 'Нет доступа к шаблону браслетов' };
            }
            return { payload: [], error: normalized.message };
          }
        }
        if (parsed.value > 0 || row.hasExisting) {
          resolved.push({ braceletTypeId: braceletTypeId!, count: parsed.value });
        }
      }
      if (updates.length > 0) {
        setBracelets((prev) =>
          prev.map((item) => {
            const update = updates.find((entry) => entry.key === item.key);
            if (!update) return item;
            return {
              ...item,
              braceletTypeId: update.braceletTypeId,
              savedToTemplate: update.savedToTemplate,
              isCustom: false,
            };
          }),
        );
      }
      return { payload: resolved };
    },
    [bracelets, clubId, details, onForbidden],
  );

  const buildUpdatePayload = useCallback(async () => {
    const womenParsed = parseNonNegativeInt(peopleWomen);
    if (womenParsed.error || womenParsed.value === null) return { payload: null, error: 'Проверьте число женщин' };
    const menParsed = parseNonNegativeInt(peopleMen);
    if (menParsed.error || menParsed.value === null) return { payload: null, error: 'Проверьте число мужчин' };
    const rejectedParsed = parseNonNegativeInt(peopleRejected);
    if (rejectedParsed.error || rejectedParsed.value === null) return { payload: null, error: 'Проверьте число отказов' };

    const braceletResult = await resolveBraceletPayload();
    if (braceletResult.error) return { payload: null, error: braceletResult.error };

    const revenuePayload: AdminShiftReportUpdatePayload['revenueEntries'] = [];
    for (const entry of revenueEntries) {
      const parsed = parseNonNegativeAmount(entry.amount);
      if (parsed.error || parsed.value === null) {
        return { payload: null, error: `Статья "${entry.name || 'без названия'}": ${parsed.error}` };
      }
      if (!entry.articleId && entry.isCustom) {
        const name = entry.name.trim();
        if (!name) {
          return { payload: null, error: 'Укажите название для разовой статьи' };
        }
        if (!entry.groupId) {
          return { payload: null, error: 'Укажите группу для разовой статьи' };
        }
      }
      if (parsed.value > 0 || entry.hasExisting || entry.isCustom) {
        revenuePayload.push({
          articleId: entry.articleId ?? null,
          name: entry.articleId ? undefined : entry.name.trim() || null,
          groupId: entry.groupId ?? null,
          amountMinor: parsed.value,
          includeInTotal: entry.includeInTotal,
          showSeparately: entry.showSeparately,
          orderIndex: entry.orderIndex ?? null,
        });
      }
    }

    const payload: AdminShiftReportUpdatePayload = {
      peopleWomen: womenParsed.value,
      peopleMen: menParsed.value,
      peopleRejected: rejectedParsed.value,
      comment: comment.trim() ? comment.trim() : null,
      bracelets: braceletResult.payload,
      revenueEntries: revenuePayload,
    };
    return { payload };
  }, [comment, peopleMen, peopleRejected, peopleWomen, resolveBraceletPayload, revenueEntries]);

  const handleSaveReport = useCallback(async () => {
    if (!details) return;
    if (isSaving) return;
    setFormError('');
    setCommentError('');
    setIsSaving(true);
    try {
      const update = await buildUpdatePayload();
      if (update.error || !update.payload) {
        setFormError(update.error ?? 'Проверьте данные');
        return;
      }
      const response = await updateShiftReport(details.report.clubId, details.report.id, update.payload);
      setDetails(response);
      setPeopleWomen(String(response.report.peopleWomen));
      setPeopleMen(String(response.report.peopleMen));
      setPeopleRejected(String(response.report.peopleRejected));
      setComment(response.report.comment ?? '');
      setBracelets(buildBraceletRows(response));
      setRevenueEntries(buildRevenueRows(response));
      addToast('Отчет обновлен');
    } catch (error) {
      const normalized = error as AdminApiError;
      if (normalized.status === 401) {
        setIsUnauthorized(true);
        return;
      }
      if (normalized.status === 403) {
        onForbidden();
        return;
      }
      setFormError(normalized.message);
    } finally {
      setIsSaving(false);
    }
  }, [addToast, buildUpdatePayload, details, isSaving, onForbidden]);

  const handleCloseReport = useCallback(async () => {
    if (!details || isClosing) return;
    setFormError('');
    setCommentError('');
    setIsClosing(true);
    try {
      const update = await buildUpdatePayload();
      if (update.error || !update.payload) {
        setFormError(update.error ?? 'Проверьте данные');
        return;
      }
      const updated = await updateShiftReport(details.report.clubId, details.report.id, update.payload);
      const closed = await closeShiftReport(details.report.clubId, details.report.id);
      setDetails((prev) =>
        prev
          ? { ...prev, report: closed.report, totals: closed.totals }
          : {
              report: closed.report,
              template: updated.template,
              totals: closed.totals,
              nonTotalIndicators: updated.nonTotalIndicators,
              depositHints: updated.depositHints,
            },
      );
      setPeopleWomen(String(updated.report.peopleWomen));
      setPeopleMen(String(updated.report.peopleMen));
      setPeopleRejected(String(updated.report.peopleRejected));
      setComment(updated.report.comment ?? '');
      setBracelets(buildBraceletRows(updated));
      setRevenueEntries(buildRevenueRows(updated));
      addToast('Смена закрыта');
    } catch (error) {
      const normalized = error as AdminApiError;
      if (normalized.status === 401) {
        setIsUnauthorized(true);
        return;
      }
      if (normalized.status === 403) {
        onForbidden();
        return;
      }
      if (normalized.code === 'validation_error' && normalized.details?.comment === 'required_for_mismatch') {
        setCommentError('Нужен комментарий при расхождении депозитов');
        commentRef.current?.focus();
        return;
      }
      setFormError(normalized.message);
    } finally {
      setIsClosing(false);
    }
  }, [addToast, buildUpdatePayload, details, isClosing, onForbidden]);

  const entriesByGroup = useMemo(() => {
    const grouped = new Map<number, RevenueEntryRow[]>();
    revenueEntries.forEach((entry) => {
      if (!entry.groupId) return;
      const list = grouped.get(entry.groupId) ?? [];
      list.push(entry);
      grouped.set(entry.groupId, list);
    });
    return grouped;
  }, [revenueEntries]);

  const disabledGroupEntries = useMemo(() => {
    const enabledIds = new Set(enabledGroups.map((group) => group.id));
    return revenueEntries.filter((entry) => entry.groupId && !enabledIds.has(entry.groupId));
  }, [enabledGroups, revenueEntries]);

  const nonTotalEntries = useMemo(() => revenueEntries.filter((entry) => !entry.includeInTotal), [revenueEntries]);
  const indicatorEntries = useMemo(
    () => revenueEntries.filter((entry) => !entry.includeInTotal && entry.showSeparately),
    [revenueEntries],
  );

  if (isUnauthorized) {
    return <AuthorizationRequired />;
  }

  return (
    <div className="px-4 py-6 space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-gray-900">Финансы · Закрытие смены</h2>
        <div className="mt-3 rounded-lg bg-white p-4 shadow-sm space-y-4">
          <label className="text-xs text-gray-500">
            Клуб
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={clubId ?? ''}
              onChange={(event) => {
                const value = Number(event.target.value);
                onSelectClub(Number.isFinite(value) && value > 0 ? value : null);
              }}
              disabled={clubsStatus === 'loading'}
            >
              <option value="">Выберите клуб</option>
              {clubs.map((club) => (
                <option key={club.id} value={club.id}>
                  {club.name}
                </option>
              ))}
            </select>
          </label>
          {clubsStatus === 'loading' && <div className="text-xs text-gray-500">Загрузка клубов...</div>}
          {clubsStatus === 'error' && (
            <div className="space-y-2 text-xs text-red-600">
              <div>{clubsError || 'Не удалось загрузить клубы'}</div>
              {clubsCanRetry && (
                <button type="button" className="rounded border border-red-200 px-3 py-1" onClick={loadClubs}>
                  Повторить
                </button>
              )}
            </div>
          )}
          <label className="text-xs text-gray-500">
            Ночь
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={selectedNight}
              onChange={(event) => setSelectedNight(event.target.value)}
              disabled={!clubId || nightsStatus === 'loading'}
            >
              <option value="">Выберите ночь</option>
              {nights.map((night) => (
                <option key={night.startUtc} value={night.startUtc}>
                  {night.name}
                </option>
              ))}
            </select>
          </label>
          {nightsStatus === 'loading' && <div className="text-xs text-gray-500">Загрузка ночей...</div>}
          {nightsStatus === 'error' && (
            <div className="space-y-2 text-xs text-red-600">
              <div>{nightsError || 'Не удалось загрузить ночи'}</div>
              {nightsCanRetry && (
                <button type="button" className="rounded border border-red-200 px-3 py-1" onClick={loadNights}>
                  Повторить
                </button>
              )}
            </div>
          )}
        </div>
      </div>

      {!clubId && <div className="text-sm text-gray-500">Выберите клуб, чтобы начать работу с отчетом.</div>}
      {clubId && !selectedNight && <div className="text-sm text-gray-500">Выберите ночь, чтобы открыть отчет.</div>}
      {status === 'loading' && <div className="text-sm text-gray-500">Загрузка отчета...</div>}
      {status === 'error' && (
        <div className="space-y-2 text-sm text-red-600">
          <div>{errorMessage || 'Не удалось загрузить отчет'}</div>
          {canRetry && (
            <button type="button" className="rounded border border-red-200 px-3 py-1 text-red-600" onClick={reload}>
              Повторить
            </button>
          )}
        </div>
      )}
      {status === 'forbidden' && <div className="text-sm text-gray-500">Нет доступа к отчету.</div>}

      {details && status === 'ready' && (
        <div className="space-y-6">
          <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
            <div className="flex items-center justify-between">
              <div className="text-sm font-semibold text-gray-900">Отчет смены</div>
              {isClosed && <span className="rounded bg-gray-100 px-2 py-1 text-xs text-gray-600">Закрыта</span>}
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-xs text-gray-500">
                Женщины
                <input
                  className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
                  value={peopleWomen}
                  onChange={(event) => setPeopleWomen(event.target.value)}
                  disabled={isClosed}
                />
              </label>
              <label className="text-xs text-gray-500">
                Мужчины
                <input
                  className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
                  value={peopleMen}
                  onChange={(event) => setPeopleMen(event.target.value)}
                  disabled={isClosed}
                />
              </label>
              <label className="text-xs text-gray-500">
                Отказы
                <input
                  className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
                  value={peopleRejected}
                  onChange={(event) => setPeopleRejected(event.target.value)}
                  disabled={isClosed}
                />
              </label>
            </div>
            <label className="text-xs text-gray-500">
              Комментарий
              <textarea
                ref={commentRef}
                className={`mt-1 w-full rounded-md border p-2 text-sm ${commentError ? 'border-red-400' : 'border-gray-200'}`}
                value={comment}
                onChange={(event) => setComment(event.target.value)}
                disabled={isClosed}
                rows={3}
              />
              {commentError && <div className="mt-1 text-xs text-red-500">{commentError}</div>}
            </label>
          </div>

          <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
            <div className="flex items-center justify-between">
              <div className="text-sm font-semibold text-gray-900">Браслеты</div>
              <button
                type="button"
                className="rounded border border-blue-200 px-2 py-1 text-xs text-blue-600"
                onClick={handleAddBracelet}
                disabled={isClosed}
              >
                + добавить
              </button>
            </div>
            <div className="space-y-2">
              {bracelets.map((row) => (
                <div key={row.key} className="grid gap-2 md:grid-cols-[2fr_1fr_auto] items-center">
                  <div className="text-xs text-gray-500">
                    {row.isCustom ? (
                      <input
                        className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
                        value={row.name}
                        onChange={(event) =>
                          setBracelets((prev) => prev.map((item) => (item.key === row.key ? { ...item, name: event.target.value } : item)))
                        }
                        placeholder="Название браслета"
                        disabled={isClosed}
                      />
                    ) : (
                      <div className="text-sm text-gray-900">
                        {row.name}
                        {!row.enabled && <span className="ml-2 text-xs text-gray-400">(архив)</span>}
                      </div>
                    )}
                  </div>
                  <input
                    className="w-full rounded-md border border-gray-200 p-2 text-sm"
                    value={row.count}
                    onChange={(event) => handleBraceletChange(row.key, event.target.value)}
                    disabled={isClosed}
                  />
                  {row.isCustom ? (
                    <div className="flex flex-col gap-2">
                      <button
                        type="button"
                        className="rounded border border-blue-200 px-2 py-1 text-xs text-blue-600"
                        onClick={() => handleSaveBraceletToTemplate(row)}
                        disabled={isClosed}
                      >
                        Сохранить в шаблон
                      </button>
                      <button
                        type="button"
                        className="rounded border border-gray-200 px-2 py-1 text-xs text-gray-600"
                        onClick={() => handleRemoveBracelet(row.key)}
                        disabled={isClosed}
                      >
                        Удалить
                      </button>
                    </div>
                  ) : (
                    <div />
                  )}
                </div>
              ))}
              {bracelets.length === 0 && <div className="text-xs text-gray-500">Нет типов браслетов.</div>}
            </div>
          </div>

          <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-4">
            <div className="flex items-center justify-between">
              <div className="text-sm font-semibold text-gray-900">Выручка</div>
              <button
                type="button"
                className="rounded border border-blue-200 px-2 py-1 text-xs text-blue-600"
                onClick={handleAddRevenueEntry}
                disabled={isClosed}
              >
                + разово
              </button>
            </div>

            {enabledGroups.map((group) => {
              const groupEntries = entriesByGroup.get(group.id) ?? [];
              return (
                <div key={group.id} className="space-y-2">
                  <div className="text-xs font-semibold text-gray-500">{group.name}</div>
                  {groupEntries.length === 0 && <div className="text-xs text-gray-400">Нет статей</div>}
                  {groupEntries.map((entry) => (
                    <div key={entry.key} className="grid gap-2 md:grid-cols-[2fr_1fr_auto] items-center">
                      <div className="text-xs text-gray-500">
                        {entry.isCustom ? (
                          <input
                            className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
                            value={entry.name}
                            onChange={(event) =>
                              setRevenueEntries((prev) =>
                                prev.map((item) => (item.key === entry.key ? { ...item, name: event.target.value } : item)),
                              )
                            }
                            placeholder="Название статьи"
                            disabled={isClosed}
                          />
                        ) : (
                          <div className="text-sm text-gray-900">
                            {entry.name}
                            {!entry.isTemplateArticle && <span className="ml-2 text-xs text-gray-400">(архив)</span>}
                          </div>
                        )}
                        {entry.isCustom && (
                          <select
                            className="mt-2 w-full rounded-md border border-gray-200 p-2 text-sm"
                            value={entry.groupId ?? ''}
                            onChange={(event) => handleRevenueToggle(entry.key, { groupId: Number(event.target.value) || null })}
                            disabled={isClosed}
                          >
                            <option value="">Выберите группу</option>
                            {enabledGroups.map((groupOption) => (
                              <option key={groupOption.id} value={groupOption.id}>
                                {groupOption.name}
                              </option>
                            ))}
                          </select>
                        )}
                      </div>
                      <div className="space-y-2">
                        <input
                          className="w-full rounded-md border border-gray-200 p-2 text-sm"
                          value={entry.amount}
                          onChange={(event) => handleRevenueAmountChange(entry.key, event.target.value)}
                          disabled={isClosed}
                        />
                        <div className="flex gap-3 text-xs text-gray-500">
                          <label className="flex items-center gap-2">
                            <input
                              type="checkbox"
                              checked={entry.includeInTotal}
                              onChange={(event) => handleRevenueToggle(entry.key, { includeInTotal: event.target.checked })}
                              disabled={isClosed}
                            />
                            В итог
                          </label>
                          <label className="flex items-center gap-2">
                            <input
                              type="checkbox"
                              checked={entry.showSeparately}
                              onChange={(event) => handleRevenueToggle(entry.key, { showSeparately: event.target.checked })}
                              disabled={isClosed}
                            />
                            Индикатор
                          </label>
                        </div>
                      </div>
                      {entry.isCustom ? (
                        <div className="flex flex-col gap-2">
                          <button
                            type="button"
                            className="rounded border border-blue-200 px-2 py-1 text-xs text-blue-600"
                            onClick={() => handleSaveRevenueToTemplate(entry)}
                            disabled={isClosed}
                          >
                            Сохранить в шаблон
                          </button>
                          <button
                            type="button"
                            className="rounded border border-gray-200 px-2 py-1 text-xs text-gray-600"
                            onClick={() => handleRemoveRevenueEntry(entry.key)}
                            disabled={isClosed}
                          >
                            Удалить
                          </button>
                        </div>
                      ) : (
                        <div />
                      )}
                    </div>
                  ))}
                </div>
              );
            })}

            {revenueEntries.filter((entry) => !entry.groupId).length > 0 && (
              <div className="space-y-2">
                <div className="text-xs font-semibold text-gray-500">Без группы</div>
                {revenueEntries
                  .filter((entry) => !entry.groupId)
                  .map((entry) => (
                    <div key={entry.key} className="grid gap-2 md:grid-cols-[2fr_1fr_auto] items-center">
                      <div className="text-sm text-gray-900">{entry.name || 'Без названия'}</div>
                      <input
                        className="w-full rounded-md border border-gray-200 p-2 text-sm"
                        value={entry.amount}
                        onChange={(event) => handleRevenueAmountChange(entry.key, event.target.value)}
                        disabled={isClosed}
                      />
                      <div className="flex items-center gap-2 text-xs text-gray-500">
                        <span>В итог: {entry.includeInTotal ? 'да' : 'нет'}</span>
                        <span>·</span>
                        <span>Индикатор: {entry.showSeparately ? 'да' : 'нет'}</span>
                      </div>
                    </div>
                  ))}
              </div>
            )}

            {disabledGroupEntries.length > 0 && (
              <div className="space-y-2">
                <div className="text-xs font-semibold text-gray-500">Архивные группы</div>
                {disabledGroupEntries.map((entry) => {
                  const group = templateGroups.find((item) => item.id === entry.groupId);
                  return (
                    <div key={entry.key} className="grid gap-2 md:grid-cols-[2fr_1fr_auto] items-center">
                      <div className="text-sm text-gray-900">
                        {entry.name} · {normalizeGroupLabel(group)}
                      </div>
                      <input
                        className="w-full rounded-md border border-gray-200 p-2 text-sm"
                        value={entry.amount}
                        onChange={(event) => handleRevenueAmountChange(entry.key, event.target.value)}
                        disabled={isClosed}
                      />
                      <div className="flex items-center gap-2 text-xs text-gray-500">
                        <span>В итог: {entry.includeInTotal ? 'да' : 'нет'}</span>
                        <span>·</span>
                        <span>Индикатор: {entry.showSeparately ? 'да' : 'нет'}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
            <div className="text-sm font-semibold text-gray-900">Итоги</div>
            <div className="text-sm text-gray-700">Итого по выручке: {totals.totalAmount}</div>
            <div className="space-y-1 text-xs text-gray-600">
              {Array.from(totals.totalsByGroup.entries()).map(([groupId, amount]) => {
                const group = templateGroups.find((item) => item.id === groupId);
                return (
                  <div key={groupId} className="flex items-center justify-between">
                    <span>{normalizeGroupLabel(group)}</span>
                    <span>{amount}</span>
                  </div>
                );
              })}
              {totals.totalsByGroup.size === 0 && <div>Нет данных по группам.</div>}
            </div>
            <div className="pt-2 border-t border-gray-100 space-y-2 text-xs text-gray-600">
              <div className="font-semibold text-gray-500">Не в итого</div>
              {nonTotalEntries.length === 0 && <div>Нет статей вне итога.</div>}
              {nonTotalEntries.map((entry) => (
                <div key={entry.key} className="flex items-center justify-between">
                  <span>{entry.name || 'Без названия'}</span>
                  <span>{entry.amount}</span>
                </div>
              ))}
              <div className="pt-2 font-semibold text-gray-500">Индикаторы</div>
              {indicatorEntries.length === 0 && <div>Нет индикаторов.</div>}
              {indicatorEntries.map((entry) => (
                <div key={`indicator-${entry.key}`} className="flex items-center justify-between">
                  <span>{entry.name || 'Без названия'}</span>
                  <span>{entry.amount}</span>
                </div>
              ))}
            </div>
            <div className="pt-2 border-t border-gray-100 space-y-1 text-xs text-gray-600">
              <div>Индикаторы вне итога: {indicatorsSum}</div>
              <div className={depositMismatch.mismatch > 0 ? 'text-red-600' : 'text-gray-600'}>
                Депозиты по столам: {depositMismatch.sumDeposits} · Расхождение: {depositMismatch.mismatch}
              </div>
            </div>
          </div>

          <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
            <div className="text-sm font-semibold text-gray-900">Подсказки по столам</div>
            <div className="text-xs text-gray-600">
              Сумма депозитов за ночь: {details.depositHints.sumDepositsForNight}
            </div>
            <div className="space-y-1 text-xs text-gray-600">
              {Object.entries(details.depositHints.allocationSummaryForNight).map(([key, value]) => (
                <div key={key} className="flex items-center justify-between">
                  <span>{key}</span>
                  <span>{value}</span>
                </div>
              ))}
              {Object.keys(details.depositHints.allocationSummaryForNight).length === 0 && <div>Нет распределений.</div>}
            </div>
          </div>

          {formError && <div className="text-sm text-red-600">{formError}</div>}

          <div className="flex flex-col gap-2 md:flex-row">
            <button
              type="button"
              className="rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
              onClick={handleSaveReport}
              disabled={isSaving || isClosing || isClosed}
            >
              {isSaving ? 'Сохранение...' : 'Сохранить'}
            </button>
            <button
              type="button"
              className="rounded border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 disabled:opacity-50"
              onClick={handleCloseReport}
              disabled={isSaving || isClosing || isClosed}
            >
              {isClosing ? 'Закрытие...' : 'Закрыть смену'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
