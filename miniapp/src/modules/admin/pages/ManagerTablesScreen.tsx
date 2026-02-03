import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { useTelegram } from '../../../app/providers/TelegramProvider';
import { useUiStore } from '../../../shared/store/ui';
import { http } from '../../../shared/api/http';
import { AdminClub, AdminApiError, listClubs } from '../api/admin.api';
import {
  AdminDepositAllocation,
  AdminNightTable,
  seatTable,
  freeTable,
  updateDeposit,
} from '../api/adminTableOps.api';
import { useNightTables } from '../hooks/useNightTables';
import { NightDto } from '../../../shared/types';
import AuthorizationRequired from '../../../shared/ui/AuthorizationRequired';
import { isRequestCanceled } from '../../../shared/api/error';

type AllocationInputs = Record<string, string>;

type AllocationCategory = {
  code: string;
  label: string;
};

const allocationCategories: AllocationCategory[] = [
  { code: 'BAR', label: 'Бар' },
  { code: 'HOOKAH', label: 'Кальян' },
  { code: 'VIP', label: 'VIP' },
  { code: 'OTHER', label: 'Другое' },
];

const buildAllocationInputs = (categories: AllocationCategory[], allocations?: AdminDepositAllocation[]): AllocationInputs => {
  const inputs: AllocationInputs = {};
  categories.forEach((category) => {
    inputs[category.code] = '';
  });
  allocations?.forEach((allocation) => {
    const code = allocation.categoryCode.toUpperCase();
    if (!(code in inputs)) {
      inputs[code] = '';
    }
    inputs[code] = String(allocation.amountMinor);
  });
  return inputs;
};

const getCategoriesForAllocations = (allocations?: AdminDepositAllocation[]): AllocationCategory[] => {
  const codes = new Set(allocationCategories.map((c) => c.code));
  const extras =
    allocations
      ?.map((allocation) => allocation.categoryCode.toUpperCase())
      .filter((code) => !codes.has(code))
      .map((code) => ({ code, label: code })) ?? [];
  return [...allocationCategories, ...extras];
};

const parseAmount = (value: string, label: string, allowEmpty = false): { value: number | null; error?: string } => {
  const trimmed = value.trim();
  if (!trimmed) {
    return allowEmpty ? { value: 0 } : { value: null, error: `Укажите ${label}` };
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || parsed < 0 || !Number.isInteger(parsed)) {
    return { value: null, error: `${label} должна быть целым неотрицательным числом` };
  }
  return { value: parsed };
};

const validateAllocations = (
  amountInput: string,
  allocationInputs: AllocationInputs,
  categories: AllocationCategory[],
): { amount: number | null; allocations: Array<{ categoryCode: string; amount: number }>; error?: string } => {
  const amountParsed = parseAmount(amountInput, 'сумму депозита');
  if (amountParsed.error) {
    return { amount: null, allocations: [], error: amountParsed.error };
  }
  let total = 0;
  const allocations = categories.map((category) => {
    const parsed = parseAmount(allocationInputs[category.code] ?? '', `сумму ${category.label}`, true);
    if (parsed.error) {
      throw new Error(parsed.error);
    }
    const value = parsed.value ?? 0;
    total += value;
    return { categoryCode: category.code, amount: value };
  });
  if (amountParsed.value !== total) {
    return {
      amount: amountParsed.value,
      allocations,
      error: 'Сумма распределений должна совпадать с суммой депозита',
    };
  }
  return { amount: amountParsed.value, allocations };
};

type ManagerTablesScreenProps = {
  clubId: number | null;
  onSelectClub: (id: number | null) => void;
  onForbidden: () => void;
};

type SeatModalState = {
  isOpen: boolean;
  table: AdminNightTable | null;
  mode: 'WITH_QR' | 'NO_QR';
  qr: string;
  amount: string;
  allocations: AllocationInputs;
  categories: AllocationCategory[];
  error: string;
  isSaving: boolean;
};

type EditDepositState = {
  isOpen: boolean;
  table: AdminNightTable | null;
  amount: string;
  allocations: AllocationInputs;
  categories: AllocationCategory[];
  reason: string;
  error: string;
  isSaving: boolean;
};

export default function ManagerTablesScreen({ clubId, onSelectClub, onForbidden }: ManagerTablesScreenProps) {
  const { addToast } = useUiStore();
  const webApp = useTelegram();
  const [clubs, setClubs] = useState<AdminClub[]>([]);
  const [clubsStatus, setClubsStatus] = useState<'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'>('idle');
  const [clubsError, setClubsError] = useState('');
  const [clubsCanRetry, setClubsCanRetry] = useState(false);
  const [nights, setNights] = useState<NightDto[]>([]);
  const [selectedNight, setSelectedNight] = useState<string>('');
  const [nightsStatus, setNightsStatus] = useState<'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'>('idle');
  const [nightsError, setNightsError] = useState('');
  const [nightsCanRetry, setNightsCanRetry] = useState(false);
  const [isUnauthorized, setIsUnauthorized] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const nightsAbortRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);
  const nightRequestIdRef = useRef(0);

  const { status, data, errorMessage, canRetry, reload } = useNightTables(clubId ?? undefined, selectedNight);

  useEffect(() => {
    if (status === 'unauthorized') {
      setIsUnauthorized(true);
    }
  }, [status]);

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
  }, [clubId, selectedNight]);

  useEffect(() => {
    if (!clubId) return;
    void loadNights();
    return () => nightsAbortRef.current?.abort();
  }, [clubId, loadNights]);

  const [seatState, setSeatState] = useState<SeatModalState>({
    isOpen: false,
    table: null,
    mode: 'NO_QR',
    qr: '',
    amount: '',
    allocations: buildAllocationInputs(allocationCategories),
    categories: allocationCategories,
    error: '',
    isSaving: false,
  });

  const [editState, setEditState] = useState<EditDepositState>({
    isOpen: false,
    table: null,
    amount: '',
    allocations: buildAllocationInputs(allocationCategories),
    categories: allocationCategories,
    reason: '',
    error: '',
    isSaving: false,
  });

  const openSeatModal = useCallback((table: AdminNightTable) => {
    setSeatState({
      isOpen: true,
      table,
      mode: 'NO_QR',
      qr: '',
      amount: '',
      allocations: buildAllocationInputs(allocationCategories),
      categories: allocationCategories,
      error: '',
      isSaving: false,
    });
  }, []);

  const openEditModal = useCallback((table: AdminNightTable) => {
    const allocations = table.activeDeposit?.allocations ?? [];
    const categories = getCategoriesForAllocations(allocations);
    setEditState({
      isOpen: true,
      table,
      amount: table.activeDeposit ? String(table.activeDeposit.amountMinor) : '',
      allocations: buildAllocationInputs(categories, allocations),
      categories,
      reason: '',
      error: '',
      isSaving: false,
    });
  }, []);

  const closeSeatModal = useCallback(() => {
    setSeatState((prev) => ({ ...prev, isOpen: false, table: null, error: '', isSaving: false }));
  }, []);

  const closeEditModal = useCallback(() => {
    setEditState((prev) => ({ ...prev, isOpen: false, table: null, error: '', isSaving: false }));
  }, []);

  const scanQr = useCallback(() => {
    if (!webApp) return;
    const handleQrText = ({ data }: { data: string }) => {
      setSeatState((prev) => ({ ...prev, qr: data }));
      webApp.offEvent('qrTextReceived', handleQrText);
      webApp.offEvent('scanQrPopupClosed', handleClosed);
      webApp.closeScanQrPopup();
    };
    const handleClosed = () => {
      webApp.offEvent('qrTextReceived', handleQrText);
      webApp.offEvent('scanQrPopupClosed', handleClosed);
    };
    webApp.onEvent('qrTextReceived', handleQrText);
    webApp.onEvent('scanQrPopupClosed', handleClosed);
    webApp.showScanQrPopup({});
  }, [webApp]);

  const handleSeatSubmit = useCallback(async () => {
    if (!clubId || !selectedNight || !seatState.table) return;
    if (seatState.mode === 'WITH_QR' && !seatState.qr.trim()) {
      setSeatState((prev) => ({ ...prev, error: 'Укажите QR или отсканируйте его' }));
      return;
    }
    let validation;
    try {
      validation = validateAllocations(seatState.amount, seatState.allocations, seatState.categories);
    } catch (error) {
      setSeatState((prev) => ({
        ...prev,
        error: error instanceof Error ? error.message : 'Проверьте суммы распределений',
      }));
      return;
    }
    if (validation.error || validation.amount === null) {
      setSeatState((prev) => ({ ...prev, error: validation.error ?? 'Проверьте суммы' }));
      return;
    }
    setSeatState((prev) => ({ ...prev, isSaving: true, error: '' }));
    try {
      await seatTable(
        clubId,
        selectedNight,
        seatState.table.tableId,
        {
          mode: seatState.mode,
          guestPassQr: seatState.mode === 'WITH_QR' ? seatState.qr.trim() : undefined,
          depositAmount: validation.amount,
          allocations: validation.allocations.filter((item) => item.amount > 0),
        },
      );
      addToast('Стол занят');
      closeSeatModal();
      await reload();
    } catch (error) {
      if (error instanceof AdminApiError && error.status === 401) {
        setIsUnauthorized(true);
        return;
      }
      if (error instanceof AdminApiError && error.status === 403) {
        onForbidden();
        return;
      }
      const message = error instanceof Error ? error.message : 'Не удалось посадить';
      setSeatState((prev) => ({ ...prev, error: message }));
    } finally {
      setSeatState((prev) => ({ ...prev, isSaving: false }));
    }
  }, [addToast, clubId, closeSeatModal, onForbidden, reload, seatState, selectedNight]);

  const handleFreeTable = useCallback(
    async (table: AdminNightTable) => {
      if (!clubId || !selectedNight) return;
      try {
        await freeTable(clubId, selectedNight, table.tableId);
        addToast('Стол освобожден');
        await reload();
      } catch (error) {
        if (error instanceof AdminApiError && error.status === 401) {
          setIsUnauthorized(true);
          return;
        }
        if (error instanceof AdminApiError && error.status === 403) {
          onForbidden();
          return;
        }
        const message = error instanceof Error ? error.message : 'Не удалось освободить стол';
        addToast(message);
      }
    },
    [addToast, clubId, onForbidden, reload, selectedNight],
  );

  const handleUpdateDeposit = useCallback(async () => {
    if (!clubId || !selectedNight || !editState.table?.activeDeposit) return;
    const trimmedReason = editState.reason.trim();
    if (!trimmedReason) {
      setEditState((prev) => ({ ...prev, error: 'Укажите причину изменения' }));
      return;
    }
    let validation;
    try {
      validation = validateAllocations(editState.amount, editState.allocations, editState.categories);
    } catch (error) {
      setEditState((prev) => ({
        ...prev,
        error: error instanceof Error ? error.message : 'Проверьте суммы распределений',
      }));
      return;
    }
    if (validation.error || validation.amount === null) {
      setEditState((prev) => ({ ...prev, error: validation.error ?? 'Проверьте суммы' }));
      return;
    }
    setEditState((prev) => ({ ...prev, isSaving: true, error: '' }));
    try {
      await updateDeposit(clubId, selectedNight, editState.table.activeDeposit.id, {
        amount: validation.amount,
        allocations: validation.allocations.filter((item) => item.amount > 0),
        reason: trimmedReason,
      });
      addToast('Депозит обновлен');
      closeEditModal();
      await reload();
    } catch (error) {
      if (error instanceof AdminApiError && error.status === 401) {
        setIsUnauthorized(true);
        return;
      }
      if (error instanceof AdminApiError && error.status === 403) {
        onForbidden();
        return;
      }
      const message = error instanceof Error ? error.message : 'Не удалось обновить депозит';
      setEditState((prev) => ({ ...prev, error: message }));
    } finally {
      setEditState((prev) => ({ ...prev, isSaving: false }));
    }
  }, [addToast, clubId, closeEditModal, editState, onForbidden, reload, selectedNight]);

  const tablesContent = useMemo(() => {
    if (!clubId) {
      return <div className="text-sm text-gray-500">Выберите клуб, чтобы увидеть столы.</div>;
    }
    if (!selectedNight) {
      return <div className="text-sm text-gray-500">Выберите ночь.</div>;
    }
    if (status === 'loading') {
      return <div className="text-sm text-gray-500">Загрузка столов...</div>;
    }
    if (status === 'error') {
      return (
        <div className="space-y-2 text-sm text-red-600">
          <div>{errorMessage || 'Не удалось загрузить столы'}</div>
          {canRetry && (
            <button type="button" className="rounded border border-red-200 px-3 py-1 text-red-600" onClick={reload}>
              Повторить
            </button>
          )}
        </div>
      );
    }
    if (status === 'ready' && data && data.length === 0) {
      return <div className="text-sm text-gray-500">Столы не найдены.</div>;
    }
    if (status === 'ready' && data) {
      return (
        <div className="space-y-3">
          {data.map((table) => (
            <div key={table.tableId} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-semibold text-gray-900">
                    {table.label} · #{table.tableNumber}
                  </div>
                  <div className="text-xs text-gray-500">
                    {table.isOccupied ? 'Занят' : 'Свободен'}
                  </div>
                  {table.activeDeposit && (
                    <div className="mt-1 text-xs text-gray-600">Депозит: {table.activeDeposit.amountMinor}</div>
                  )}
                </div>
                <div className="flex flex-col gap-2 text-xs">
                  {!table.isOccupied && (
                    <button
                      type="button"
                      className="rounded border border-blue-200 px-3 py-1 text-blue-600"
                      onClick={() => openSeatModal(table)}
                    >
                      Посадить
                    </button>
                  )}
                  {table.isOccupied && (
                    <button
                      type="button"
                      className="rounded border border-gray-200 px-3 py-1 text-gray-700"
                      onClick={() => handleFreeTable(table)}
                    >
                      Освободить
                    </button>
                  )}
                  {table.activeDeposit && (
                    <button
                      type="button"
                      className="rounded border border-gray-200 px-3 py-1 text-gray-700"
                      onClick={() => openEditModal(table)}
                    >
                      Редактировать депозит
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      );
    }
    return null;
  }, [canRetry, clubId, data, errorMessage, handleFreeTable, openEditModal, openSeatModal, reload, selectedNight, status]);

  if (isUnauthorized) {
    return <AuthorizationRequired />;
  }

  return (
    <div className="px-4 py-6 space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-gray-900">Столы</h2>
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

      <div className="space-y-3">
        <div className="text-sm font-semibold text-gray-900">Список столов</div>
        {tablesContent}
      </div>

      {seatState.isOpen && seatState.table && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-lg bg-white p-5 space-y-4">
            <div className="text-sm font-semibold text-gray-900">Посадить: {seatState.table.label}</div>
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-2">
                {(['WITH_QR', 'NO_QR'] as const).map((mode) => (
                  <button
                    key={mode}
                    type="button"
                    className={`rounded border px-3 py-2 text-xs ${
                      seatState.mode === mode ? 'border-blue-600 bg-blue-50 text-blue-700' : 'border-gray-200 text-gray-600'
                    }`}
                    onClick={() => setSeatState((prev) => ({ ...prev, mode }))}
                  >
                    {mode === 'WITH_QR' ? 'С QR' : 'Без QR'}
                  </button>
                ))}
              </div>
              {seatState.mode === 'WITH_QR' && (
                <div className="space-y-2">
                  <input
                    className="w-full rounded-md border border-gray-200 p-2 text-sm"
                    placeholder="QR строка"
                    value={seatState.qr}
                    onChange={(event) => setSeatState((prev) => ({ ...prev, qr: event.target.value }))}
                  />
                  <button
                    type="button"
                    className="w-full rounded-md border border-gray-200 px-3 py-2 text-xs text-gray-700"
                    onClick={scanQr}
                  >
                    Сканировать QR
                  </button>
                </div>
              )}
              <input
                className="w-full rounded-md border border-gray-200 p-2 text-sm"
                placeholder="Сумма депозита"
                inputMode="numeric"
                value={seatState.amount}
                onChange={(event) => setSeatState((prev) => ({ ...prev, amount: event.target.value }))}
              />
              <div className="space-y-2">
                {seatState.categories.map((category) => (
                  <label key={category.code} className="block text-xs text-gray-500">
                    {category.label}
                    <input
                      className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
                      inputMode="numeric"
                      value={seatState.allocations[category.code] ?? ''}
                      onChange={(event) =>
                        setSeatState((prev) => ({
                          ...prev,
                          allocations: { ...prev.allocations, [category.code]: event.target.value },
                        }))
                      }
                    />
                  </label>
                ))}
              </div>
              {seatState.error && <div className="text-xs text-red-600">{seatState.error}</div>}
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                className="flex-1 rounded-md border border-gray-200 px-4 py-2 text-sm text-gray-700"
                onClick={closeSeatModal}
                disabled={seatState.isSaving}
              >
                Отмена
              </button>
              <button
                type="button"
                className="flex-1 rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
                onClick={handleSeatSubmit}
                disabled={seatState.isSaving}
              >
                {seatState.isSaving ? 'Сохранение...' : 'Сохранить'}
              </button>
            </div>
          </div>
        </div>
      )}

      {editState.isOpen && editState.table?.activeDeposit && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-lg bg-white p-5 space-y-4">
            <div className="text-sm font-semibold text-gray-900">Редактировать депозит</div>
            <div className="space-y-3">
              <input
                className="w-full rounded-md border border-gray-200 p-2 text-sm"
                placeholder="Сумма депозита"
                inputMode="numeric"
                value={editState.amount}
                onChange={(event) => setEditState((prev) => ({ ...prev, amount: event.target.value }))}
              />
              <div className="space-y-2">
                {editState.categories.map((category) => (
                  <label key={category.code} className="block text-xs text-gray-500">
                    {category.label}
                    <input
                      className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
                      inputMode="numeric"
                      value={editState.allocations[category.code] ?? ''}
                      onChange={(event) =>
                        setEditState((prev) => ({
                          ...prev,
                          allocations: { ...prev.allocations, [category.code]: event.target.value },
                        }))
                      }
                    />
                  </label>
                ))}
              </div>
              <textarea
                className="w-full rounded-md border border-gray-200 p-2 text-sm"
                placeholder="Причина изменения"
                value={editState.reason}
                onChange={(event) => setEditState((prev) => ({ ...prev, reason: event.target.value }))}
                rows={3}
              />
              {editState.error && <div className="text-xs text-red-600">{editState.error}</div>}
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                className="flex-1 rounded-md border border-gray-200 px-4 py-2 text-sm text-gray-700"
                onClick={closeEditModal}
                disabled={editState.isSaving}
              >
                Отмена
              </button>
              <button
                type="button"
                className="flex-1 rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
                onClick={handleUpdateDeposit}
                disabled={editState.isSaving}
              >
                {editState.isSaving ? 'Сохранение...' : 'Сохранить изменения'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
