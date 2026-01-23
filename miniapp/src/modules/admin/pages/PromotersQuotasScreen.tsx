import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AdminApiError, AdminClub, isAbortError, listClubs } from '../api/admin.api';
import {
  AdminPromoter,
  AdminPromoterQuota,
  createPromoterQuota,
  listPromoters,
  updatePromoterAccess,
  updatePromoterQuota,
} from '../api/promoters.api';
import { useUiStore } from '../../../shared/store/ui';
import { mapAdminErrorMessage } from '../utils/adminErrors';

type PromotersQuotasScreenProps = {
  clubId: number | null;
  onSelectClub: (clubId: number) => void;
  onForbidden: () => void;
};

const MAX_QUOTA = 1000;

type QuotaDraft = {
  quota: string;
};

type NewQuotaDraft = {
  tableId: string;
  quota: string;
  expiresAt: string;
};

const emptyNewQuota: NewQuotaDraft = {
  tableId: '',
  quota: '',
  expiresAt: '',
};

export default function PromotersQuotasScreen({ clubId, onSelectClub, onForbidden }: PromotersQuotasScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [clubs, setClubs] = useState<AdminClub[]>([]);
  const [clubsStatus, setClubsStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [promoters, setPromoters] = useState<AdminPromoter[]>([]);
  const [promoterStatus, setPromoterStatus] = useState<'idle' | 'loading' | 'ready' | 'error' | 'empty'>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [quotaDrafts, setQuotaDrafts] = useState<Record<string, QuotaDraft>>({});
  const [newQuotaDrafts, setNewQuotaDrafts] = useState<Record<number, NewQuotaDraft>>({});
  const [savingAccess, setSavingAccess] = useState<Record<number, boolean>>({});
  const [savingQuota, setSavingQuota] = useState<Record<string, boolean>>({});
  const [refreshIndex, setRefreshIndex] = useState(0);
  const requestIdRef = useRef(0);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setClubsStatus('loading');
    listClubs(controller.signal)
      .then((data) => {
        if (!mountedRef.current) return;
        setClubs(data);
        setClubsStatus('ready');
      })
      .catch((error: AdminApiError) => {
        if (isAbortError(error)) return;
        if (error.status === 403) {
          onForbidden();
          return;
        }
        const message = mapAdminErrorMessage(error);
        if (!mountedRef.current) return;
        setClubsStatus('error');
        setErrorMessage(message);
      });
    return () => controller.abort();
  }, [onForbidden]);

  useEffect(() => {
    if (!clubId) {
      setPromoters([]);
      setPromoterStatus('idle');
      return;
    }
    const controller = new AbortController();
    const requestId = ++requestIdRef.current;
    setPromoterStatus('loading');
    setErrorMessage(null);
    listPromoters(clubId, controller.signal)
      .then((data) => {
        if (!mountedRef.current || requestId !== requestIdRef.current) return;
        setPromoters(data);
        setQuotaDrafts({});
        setNewQuotaDrafts({});
        setPromoterStatus(data.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: AdminApiError) => {
        if (!mountedRef.current || requestId !== requestIdRef.current) return;
        if (isAbortError(error)) return;
        if (error.status === 403) {
          onForbidden();
          return;
        }
        const message = mapAdminErrorMessage(error);
        setErrorMessage(message);
        setPromoterStatus('error');
        addToast(message);
      });
    return () => controller.abort();
  }, [addToast, clubId, onForbidden, refreshIndex]);

  const handleReload = useCallback(() => setRefreshIndex((value) => value + 1), []);

  const handleQuotaChange = useCallback((promoterId: number, tableId: number, value: string) => {
    const key = `${promoterId}-${tableId}`;
    setQuotaDrafts((prev) => ({ ...prev, [key]: { quota: value } }));
  }, []);

  const handleNewQuotaChange = useCallback((promoterId: number, patch: Partial<NewQuotaDraft>) => {
    setNewQuotaDrafts((prev) => ({ ...prev, [promoterId]: { ...emptyNewQuota, ...prev[promoterId], ...patch } }));
  }, []);

  const resolveDisplayName = useCallback((promoter: AdminPromoter) => {
    return promoter.displayName || promoter.username || `tg:${promoter.telegramUserId}`;
  }, []);

  const updatePromoterState = useCallback(
    (promoterId: number, updater: (promoter: AdminPromoter) => AdminPromoter) => {
      setPromoters((prev) => prev.map((promoter) => (promoter.promoterId === promoterId ? updater(promoter) : promoter)));
    },
    [],
  );

  const handleAccessToggle = useCallback(
    async (promoter: AdminPromoter) => {
      if (!clubId || savingAccess[promoter.promoterId]) return;
      const next = !promoter.accessEnabled;
      const confirmed = window.confirm(`Вы уверены, что хотите ${next ? 'включить' : 'отключить'} доступ?`);
      if (!confirmed) return;
      setSavingAccess((prev) => ({ ...prev, [promoter.promoterId]: true }));
      try {
        const enabled = await updatePromoterAccess(promoter.promoterId, { clubId, enabled: next });
        updatePromoterState(promoter.promoterId, (item) => ({ ...item, accessEnabled: enabled }));
        addToast(enabled ? 'Доступ включен' : 'Доступ отключен');
      } catch (error) {
        const normalized = error as AdminApiError;
        if (normalized.status === 403) {
          onForbidden();
          return;
        }
        addToast(mapAdminErrorMessage(normalized));
      } finally {
        if (mountedRef.current) {
          setSavingAccess((prev) => ({ ...prev, [promoter.promoterId]: false }));
        }
      }
    },
    [addToast, clubId, onForbidden, savingAccess, updatePromoterState],
  );

  const handleSaveQuota = useCallback(
    async (promoterId: number, quota: AdminPromoterQuota) => {
      if (!clubId) return;
      const key = `${promoterId}-${quota.tableId}`;
      if (savingQuota[key]) return;
      const draftValue = quotaDrafts[key]?.quota ?? String(quota.quota);
      const parsed = Number(draftValue);
      if (!Number.isFinite(parsed) || parsed < 0 || parsed > MAX_QUOTA) {
        addToast('Квота должна быть числом от 0 до 1000');
        return;
      }
      setSavingQuota((prev) => ({ ...prev, [key]: true }));
      try {
        const updated = await updatePromoterQuota({
          clubId,
          promoterId,
          tableId: quota.tableId,
          quota: Math.floor(parsed),
          expiresAt: quota.expiresAt,
        });
        updatePromoterState(promoterId, (item) => ({
          ...item,
          quotas: item.quotas.map((itemQuota) =>
            itemQuota.tableId === updated.tableId ? { ...itemQuota, quota: updated.quota, expiresAt: updated.expiresAt } : itemQuota,
          ),
        }));
        addToast('Квота обновлена');
      } catch (error) {
        const normalized = error as AdminApiError;
        if (normalized.status === 403) {
          onForbidden();
          return;
        }
        addToast(mapAdminErrorMessage(normalized));
      } finally {
        if (mountedRef.current) {
          setSavingQuota((prev) => ({ ...prev, [key]: false }));
        }
      }
    },
    [addToast, clubId, onForbidden, quotaDrafts, savingQuota, updatePromoterState],
  );

  const handleCreateQuota = useCallback(
    async (promoterId: number) => {
      if (!clubId) return;
      const draft = newQuotaDrafts[promoterId] ?? emptyNewQuota;
      const tableId = Number(draft.tableId);
      const quotaValue = Number(draft.quota);
      const expiresAt = draft.expiresAt.trim();
      if (!Number.isFinite(tableId) || tableId <= 0) {
        addToast('Укажите корректный ID стола');
        return;
      }
      if (!Number.isFinite(quotaValue) || quotaValue < 0 || quotaValue > MAX_QUOTA) {
        addToast('Квота должна быть числом от 0 до 1000');
        return;
      }
      if (!expiresAt || Number.isNaN(Date.parse(expiresAt))) {
        addToast('Укажите корректную дату в ISO-формате');
        return;
      }
      const key = `new-${promoterId}-${tableId}`;
      if (savingQuota[key]) return;
      setSavingQuota((prev) => ({ ...prev, [key]: true }));
      try {
        const created = await createPromoterQuota({
          clubId,
          promoterId,
          tableId,
          quota: Math.floor(quotaValue),
          expiresAt,
        });
        updatePromoterState(promoterId, (item) => ({
          ...item,
          quotas: [...item.quotas, created].sort((a, b) => a.tableId - b.tableId),
        }));
        setNewQuotaDrafts((prev) => ({ ...prev, [promoterId]: emptyNewQuota }));
        addToast('Квота создана');
      } catch (error) {
        const normalized = error as AdminApiError;
        if (normalized.status === 403) {
          onForbidden();
          return;
        }
        addToast(mapAdminErrorMessage(normalized));
      } finally {
        if (mountedRef.current) {
          setSavingQuota((prev) => ({ ...prev, [key]: false }));
        }
      }
    },
    [addToast, clubId, newQuotaDrafts, onForbidden, savingQuota, updatePromoterState],
  );

  const clubOptions = useMemo(() => {
    if (clubsStatus !== 'ready') return [];
    return clubs.map((club) => ({
      value: club.id,
      label: `${club.name} (${club.city})`,
    }));
  }, [clubs, clubsStatus]);

  return (
    <div className="space-y-6 px-4 pb-8">
      <section className="rounded-lg bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">Промоутеры и квоты</h2>
          <button type="button" className="text-sm text-blue-600" onClick={handleReload} disabled={!clubId}>
            Обновить
          </button>
        </div>
        <div className="mt-4 space-y-2">
          <label htmlFor="promoter-club" className="text-sm text-gray-600">
            Клуб
          </label>
          {clubsStatus === 'loading' && <p className="text-sm text-gray-500">Загрузка списка клубов...</p>}
          {clubsStatus === 'error' && <p className="text-sm text-red-500">{errorMessage}</p>}
          {clubsStatus === 'ready' && (
            <select
              id="promoter-club"
              className="w-full rounded border border-gray-200 px-3 py-2 text-sm"
              value={clubId ?? ''}
              onChange={(event) => onSelectClub(event.target.value ? Number(event.target.value) : 0)}
            >
              <option value="">Выберите клуб</option>
              {clubOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          )}
        </div>
      </section>

      {promoterStatus === 'loading' && (
        <section className="rounded-lg bg-white p-4 text-sm text-gray-500 shadow-sm">Загрузка промоутеров...</section>
      )}
      {promoterStatus === 'error' && errorMessage && (
        <section className="rounded-lg bg-white p-4 text-sm text-red-500 shadow-sm">{errorMessage}</section>
      )}
      {promoterStatus === 'empty' && (
        <section className="rounded-lg bg-white p-4 text-sm text-gray-500 shadow-sm">Промоутеры не найдены.</section>
      )}
      {promoterStatus === 'ready' && (
        <section className="space-y-4">
          {promoters.map((promoter) => {
            const displayName = resolveDisplayName(promoter);
            return (
              <article key={promoter.promoterId} className="rounded-lg bg-white p-4 shadow-sm">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-sm font-semibold text-gray-900">{displayName}</p>
                    <p className="text-xs text-gray-500">
                      ID {promoter.promoterId} · tg {promoter.telegramUserId}
                    </p>
                  </div>
                  <label className="flex items-center gap-2 text-xs text-gray-600">
                    <input
                      type="checkbox"
                      checked={promoter.accessEnabled}
                      onChange={() => void handleAccessToggle(promoter)}
                      disabled={savingAccess[promoter.promoterId]}
                    />
                    Доступ {promoter.accessEnabled ? 'включен' : 'выключен'}
                  </label>
                </div>
                <div className="mt-4 space-y-3">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-400">Квоты</h3>
                  {promoter.quotas.length === 0 && (
                    <p className="text-xs text-gray-500">Квоты пока не заданы.</p>
                  )}
                  {promoter.quotas.map((quota) => {
                    const key = `${promoter.promoterId}-${quota.tableId}`;
                    return (
                      <div key={key} className="rounded border border-gray-100 p-3">
                        <div className="flex flex-wrap items-center gap-2 text-xs text-gray-600">
                          <span>Стол #{quota.tableId}</span>
                          <span>Held: {quota.held}</span>
                          <span className="text-gray-400">Expires: {quota.expiresAt}</span>
                        </div>
                        <div className="mt-2 flex items-center gap-2">
                          <input
                            type="number"
                            min={0}
                            max={MAX_QUOTA}
                            className="w-28 rounded border border-gray-200 px-2 py-1 text-sm"
                            value={quotaDrafts[key]?.quota ?? String(quota.quota)}
                            onChange={(event) => handleQuotaChange(promoter.promoterId, quota.tableId, event.target.value)}
                            disabled={savingQuota[key]}
                          />
                          <button
                            type="button"
                            className="rounded bg-blue-600 px-3 py-1 text-xs font-medium text-white disabled:opacity-50"
                            onClick={() => void handleSaveQuota(promoter.promoterId, quota)}
                            disabled={savingQuota[key]}
                          >
                            Сохранить
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
                <div className="mt-4 rounded border border-dashed border-gray-200 p-3">
                  <h4 className="text-xs font-semibold uppercase tracking-wide text-gray-400">Добавить квоту</h4>
                  <div className="mt-2 grid gap-2 sm:grid-cols-3">
                    <input
                      type="number"
                      min={1}
                      placeholder="ID стола"
                      className="rounded border border-gray-200 px-2 py-1 text-sm"
                      value={newQuotaDrafts[promoter.promoterId]?.tableId ?? ''}
                      onChange={(event) => handleNewQuotaChange(promoter.promoterId, { tableId: event.target.value })}
                    />
                    <input
                      type="number"
                      min={0}
                      max={MAX_QUOTA}
                      placeholder="Квота"
                      className="rounded border border-gray-200 px-2 py-1 text-sm"
                      value={newQuotaDrafts[promoter.promoterId]?.quota ?? ''}
                      onChange={(event) => handleNewQuotaChange(promoter.promoterId, { quota: event.target.value })}
                    />
                    <input
                      type="text"
                      placeholder="expiresAt (ISO)"
                      className="rounded border border-gray-200 px-2 py-1 text-sm"
                      value={newQuotaDrafts[promoter.promoterId]?.expiresAt ?? ''}
                      onChange={(event) => handleNewQuotaChange(promoter.promoterId, { expiresAt: event.target.value })}
                    />
                  </div>
                  <button
                    type="button"
                    className="mt-2 rounded bg-gray-900 px-3 py-1 text-xs font-medium text-white disabled:opacity-50"
                    onClick={() => void handleCreateQuota(promoter.promoterId)}
                  >
                    Создать квоту
                  </button>
                </div>
              </article>
            );
          })}
        </section>
      )}
    </div>
  );
}
