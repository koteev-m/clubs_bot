import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useUiStore } from '../../../shared/store/ui';
import {
  AdminApiError,
  AdminTable,
  createHallTable,
  deleteHallTable,
  fetchHallPlanBlob,
  isAbortError,
  listHallTables,
  updateHallTable,
  uploadHallPlan,
} from '../api/admin.api';
import { FieldErrors, mapAdminErrorMessage, mapValidationErrors } from '../utils/adminErrors';
import HallPlanStage from './HallPlanStage';

const emptyForm = {
  tableNumber: '',
  label: '',
  capacity: '',
};

const maxFileSizeMb = 5;

type HallEditorScreenProps = {
  clubId: number;
  hallId: number;
  onBack: () => void;
};

export default function HallEditorScreen({ clubId, hallId, onBack }: HallEditorScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [planStatus, setPlanStatus] = useState<'loading' | 'ready' | 'empty' | 'error'>('loading');
  const [planError, setPlanError] = useState<string | null>(null);
  const [planUrl, setPlanUrl] = useState<string | null>(null);
  const planEtagRef = useRef<string | null>(null);
  const planUrlRef = useRef<string | null>(null);
  const [tablesStatus, setTablesStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [tablesError, setTablesError] = useState<string | null>(null);
  const [tables, setTables] = useState<AdminTable[]>([]);
  const [selectedTableId, setSelectedTableId] = useState<number | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [busy, setBusy] = useState(false);
  const [previewMode, setPreviewMode] = useState(false);
  const [refreshIndex, setRefreshIndex] = useState(0);
  const requestIdPlanRef = useRef(0);
  const requestIdTablesRef = useRef(0);
  const mountedRef = useRef(false);

  const reload = useCallback(() => setRefreshIndex((value) => value + 1), []);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(
    () => () => {
      if (planUrl) {
        URL.revokeObjectURL(planUrl);
      }
    },
    [planUrl],
  );

  useEffect(() => {
    planUrlRef.current = planUrl;
  }, [planUrl]);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++requestIdPlanRef.current;
    setPlanStatus('loading');
    setPlanError(null);

    fetchHallPlanBlob(clubId, hallId, planEtagRef.current, controller.signal)
      .then((result) => {
        if (requestId !== requestIdPlanRef.current) return;
        if (result.status === 304) {
          setPlanStatus(planUrlRef.current ? 'ready' : 'empty');
          return;
        }
        if (result.status === 404) {
          setPlanUrl((prev) => {
            if (prev) URL.revokeObjectURL(prev);
            return null;
          });
          planEtagRef.current = null;
          setPlanStatus('empty');
          return;
        }
        if (!result.blob) {
          setPlanStatus('empty');
          return;
        }
        const nextUrl = URL.createObjectURL(result.blob);
        setPlanUrl((prev) => {
          if (prev) URL.revokeObjectURL(prev);
          return nextUrl;
        });
        planEtagRef.current = result.etag ?? null;
        setPlanStatus('ready');
      })
      .catch((error: AdminApiError) => {
        if (requestId !== requestIdPlanRef.current) return;
        if (isAbortError(error)) return;
        if (error.status === 403) {
          addToast(mapAdminErrorMessage(error));
          onBack();
          return;
        }
        const message = mapAdminErrorMessage(error);
        setPlanError(message);
        setPlanStatus('error');
        addToast(message);
      });

    return () => controller.abort();
  }, [addToast, clubId, hallId, onBack, refreshIndex]);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++requestIdTablesRef.current;
    setTablesStatus('loading');
    setTablesError(null);

    listHallTables(hallId, controller.signal)
      .then((data) => {
        if (requestId !== requestIdTablesRef.current) return;
        setTables(data);
        setTablesStatus('ready');
      })
      .catch((error: AdminApiError) => {
        if (requestId !== requestIdTablesRef.current) return;
        if (isAbortError(error)) return;
        if (error.status === 403) {
          addToast(mapAdminErrorMessage(error));
          onBack();
          return;
        }
        if (error.status === 404 && error.code === 'hall_not_found') {
          addToast(mapAdminErrorMessage(error));
          onBack();
          return;
        }
        if (error.status === 400 && error.code === 'validation_error') {
          addToast(mapAdminErrorMessage(error));
          onBack();
          return;
        }
        const message = mapAdminErrorMessage(error);
        setTablesError(message);
        setTablesStatus('error');
        addToast(message);
      });
    return () => controller.abort();
  }, [addToast, hallId, onBack, refreshIndex]);

  useEffect(() => {
    setSelectedTableId((prev) => {
      if (!prev) return null;
      return tables.some((table) => table.id === prev) ? prev : null;
    });
  }, [tables]);

  const selectedTable = useMemo(
    () => tables.find((table) => table.id === selectedTableId) ?? null,
    [selectedTableId, tables],
  );

  useEffect(() => {
    if (!selectedTable) {
      setForm(emptyForm);
      setFieldErrors({});
      return;
    }
    setForm({
      tableNumber: selectedTable.tableNumber.toString(),
      label: selectedTable.label,
      capacity: selectedTable.capacity.toString(),
    });
    setFieldErrors({});
  }, [selectedTable]);

  const nextTableNumber = useMemo(() => {
    if (!tables.length) return 1;
    return Math.max(...tables.map((table) => table.tableNumber ?? 0)) + 1;
  }, [tables]);

  const handleUploadPlan = useCallback(
    async (file: File) => {
      if (busy) return;
      if (!['image/png', 'image/jpeg'].includes(file.type)) {
        addToast('Поддерживаются только PNG и JPEG');
        return;
      }
      if (file.size > maxFileSizeMb * 1024 * 1024) {
        addToast(`Размер файла не более ${maxFileSizeMb} МБ`);
        return;
      }
      setBusy(true);
      try {
        await uploadHallPlan(hallId, file);
        if (!mountedRef.current) return;
        addToast('План зала обновлен');
        reload();
      } catch (error) {
        const normalized = error as AdminApiError;
        if (isAbortError(normalized)) return;
        addToast(mapAdminErrorMessage(normalized));
      } finally {
        if (mountedRef.current) {
          setBusy(false);
        }
      }
    },
    [addToast, busy, hallId, reload],
  );

  const handleCreateTable = useCallback(
    async (coords: { x: number; y: number }) => {
      if (busy || previewMode) return;
      setBusy(true);
      setFieldErrors({});
      try {
        const created = await createHallTable(hallId, {
          label: `Стол ${nextTableNumber}`,
          capacity: 2,
          tableNumber: nextTableNumber,
          x: coords.x,
          y: coords.y,
        });
        if (!mountedRef.current) return;
        setTables((prev) => [...prev, created]);
        setSelectedTableId(created.id);
        addToast('Стол создан');
      } catch (error) {
        const normalized = error as AdminApiError;
        if (isAbortError(normalized)) return;
        const validation = mapValidationErrors(normalized);
        if (validation) {
          if (!mountedRef.current) return;
          setFieldErrors(validation);
        } else {
          if (!mountedRef.current) return;
          addToast(mapAdminErrorMessage(normalized));
        }
      } finally {
        if (mountedRef.current) {
          setBusy(false);
        }
      }
    },
    [addToast, busy, hallId, nextTableNumber, previewMode],
  );

  const handleMoveTable = useCallback(
    async (tableId: number, coords: { x: number; y: number }) => {
      if (busy || previewMode) return;
      setBusy(true);
      try {
        const updated = await updateHallTable(hallId, tableId, { x: coords.x, y: coords.y });
        if (!mountedRef.current) return;
        setTables((prev) => prev.map((table) => (table.id === updated.id ? updated : table)));
      } catch (error) {
        const normalized = error as AdminApiError;
        if (isAbortError(normalized)) return;
        addToast(mapAdminErrorMessage(normalized));
        reload();
      } finally {
        if (mountedRef.current) {
          setBusy(false);
        }
      }
    },
    [addToast, busy, hallId, previewMode, reload],
  );

  const handleSaveMetadata = useCallback(async () => {
    if (!selectedTable || busy || previewMode) return;
    setBusy(true);
    setFieldErrors({});
    try {
      const payload = {
        label: form.label.trim(),
        capacity: Number(form.capacity),
        tableNumber: Number(form.tableNumber),
      };
      const updated = await updateHallTable(hallId, selectedTable.id, payload);
      if (!mountedRef.current) return;
      setTables((prev) => prev.map((table) => (table.id === updated.id ? updated : table)));
      addToast('Стол обновлен');
    } catch (error) {
      const normalized = error as AdminApiError;
      if (isAbortError(normalized)) return;
      const validation = mapValidationErrors(normalized);
      if (validation) {
        if (!mountedRef.current) return;
        setFieldErrors(validation);
      } else {
        if (!mountedRef.current) return;
        addToast(mapAdminErrorMessage(normalized));
      }
    } finally {
      if (mountedRef.current) {
        setBusy(false);
      }
    }
  }, [addToast, busy, form, hallId, previewMode, selectedTable]);

  const handleDeleteTable = useCallback(async () => {
    if (!selectedTable || busy || previewMode) return;
    const confirmed = window.confirm('Удалить стол?');
    if (!confirmed) return;
    setBusy(true);
    try {
      await deleteHallTable(hallId, selectedTable.id);
      if (!mountedRef.current) return;
      setTables((prev) => prev.filter((table) => table.id !== selectedTable.id));
      setSelectedTableId(null);
      addToast('Стол удален');
    } catch (error) {
      const normalized = error as AdminApiError;
      if (isAbortError(normalized)) return;
      addToast(mapAdminErrorMessage(normalized));
    } finally {
      if (mountedRef.current) {
        setBusy(false);
      }
    }
  }, [addToast, busy, hallId, previewMode, selectedTable]);

  const isReadOnly = previewMode || busy;

  return (
    <div className="space-y-6 px-4 pb-8">
      <div className="flex items-center justify-between">
        <button type="button" className="text-sm text-blue-600" onClick={onBack} disabled={busy}>
          ← Залы
        </button>
        <div className="flex items-center gap-2">
          <button
            type="button"
            className="rounded border border-gray-200 px-3 py-1 text-xs text-gray-700"
            onClick={() => setPreviewMode((value) => !value)}
            disabled={busy}
          >
            {previewMode ? 'Режим редактирования' : 'Режим превью'}
          </button>
          <button type="button" className="text-sm text-blue-600" onClick={reload} disabled={busy}>
            Обновить
          </button>
        </div>
      </div>

      <section className="rounded-lg bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">План зала</h2>
        </div>
        {planStatus === 'loading' && <p className="mt-4 text-sm text-gray-500">Загрузка плана...</p>}
        {planStatus === 'error' && (
          <div className="mt-4 space-y-2 text-sm text-gray-600">
            <p>{planError ?? 'Не удалось загрузить план'}</p>
            <button type="button" className="text-blue-600" onClick={reload} disabled={busy}>
              Повторить
            </button>
          </div>
        )}
        {planStatus === 'empty' && (
          <div className="mt-4 space-y-3 text-sm text-gray-600">
            <p>План еще не загружен.</p>
            <label className="flex flex-col gap-2">
              <span className="text-xs text-gray-500">PNG/JPEG, до 5 МБ.</span>
              <input
                type="file"
                accept="image/png,image/jpeg"
                disabled={busy || previewMode}
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (!file) return;
                  handleUploadPlan(file);
                  event.target.value = '';
                }}
              />
            </label>
          </div>
        )}
        {planStatus === 'ready' && planUrl && (
          <div className="mt-4 space-y-3">
            <HallPlanStage
              planUrl={planUrl}
              tables={tables}
              selectedTableId={selectedTableId}
              readOnly={previewMode}
              onSelectTable={(tableId) => setSelectedTableId(tableId)}
              onCreateTable={handleCreateTable}
              onMoveTable={handleMoveTable}
            />
            {!previewMode && (
              <label className="flex flex-col gap-2 text-sm text-gray-600">
                <span className="text-xs text-gray-500">Обновить план (PNG/JPEG, до 5 МБ).</span>
                <input
                  type="file"
                  accept="image/png,image/jpeg"
                  disabled={busy}
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    if (!file) return;
                    handleUploadPlan(file);
                    event.target.value = '';
                  }}
                />
              </label>
            )}
          </div>
        )}
      </section>

      <section className="rounded-lg bg-white p-4 shadow-sm">
        <h2 className="text-base font-semibold text-gray-900">Столы</h2>
        {tablesStatus === 'loading' && <p className="mt-4 text-sm text-gray-500">Загрузка...</p>}
        {tablesStatus === 'error' && (
          <div className="mt-4 space-y-2 text-sm text-gray-600">
            <p>{tablesError ?? 'Не удалось загрузить столы'}</p>
            <button type="button" className="text-blue-600" onClick={reload} disabled={busy}>
              Повторить
            </button>
          </div>
        )}
        {tablesStatus === 'ready' && tables.length === 0 && (
          <p className="mt-4 text-sm text-gray-500">Столов пока нет</p>
        )}
        {tablesStatus === 'ready' && tables.length > 0 && (
          <div className="mt-4 flex flex-wrap gap-2">
            {tables.map((table) => (
              <button
                key={table.id}
                type="button"
                className={`rounded border px-3 py-1 text-xs ${
                  table.id === selectedTableId
                    ? 'border-blue-500 bg-blue-50 text-blue-600'
                    : 'border-gray-200 text-gray-600'
                }`}
                onClick={() => setSelectedTableId(table.id)}
                disabled={busy}
              >
                #{table.tableNumber} · {table.label}
              </button>
            ))}
          </div>
        )}
        <div className="mt-6 space-y-3">
          <p className="text-sm font-medium text-gray-800">Выбранный стол</p>
          {!selectedTable && <p className="text-sm text-gray-500">Выберите стол на плане.</p>}
          {selectedTable && (
            <div className="space-y-3">
              <div>
                <label htmlFor="table-number" className="text-sm text-gray-600">
                  Номер
                </label>
                <input
                  id="table-number"
                  type="number"
                  className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
                  value={form.tableNumber}
                  onChange={(event) => setForm((prev) => ({ ...prev, tableNumber: event.target.value }))}
                  disabled={isReadOnly}
                />
                {fieldErrors.tableNumber && <p className="mt-1 text-xs text-red-500">{fieldErrors.tableNumber}</p>}
              </div>
              <div>
                <label htmlFor="table-label" className="text-sm text-gray-600">
                  Название
                </label>
                <input
                  id="table-label"
                  className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
                  value={form.label}
                  onChange={(event) => setForm((prev) => ({ ...prev, label: event.target.value }))}
                  disabled={isReadOnly}
                />
                {fieldErrors.label && <p className="mt-1 text-xs text-red-500">{fieldErrors.label}</p>}
              </div>
              <div>
                <label htmlFor="table-capacity" className="text-sm text-gray-600">
                  Вместимость
                </label>
                <input
                  id="table-capacity"
                  type="number"
                  className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
                  value={form.capacity}
                  onChange={(event) => setForm((prev) => ({ ...prev, capacity: event.target.value }))}
                  disabled={isReadOnly}
                />
                {fieldErrors.capacity && <p className="mt-1 text-xs text-red-500">{fieldErrors.capacity}</p>}
              </div>
              {fieldErrors.payload && <p className="text-xs text-red-500">{fieldErrors.payload}</p>}
              {!previewMode && (
                <div className="flex gap-2">
                  <button
                    type="button"
                    className="flex-1 rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                    onClick={handleSaveMetadata}
                    disabled={busy}
                  >
                    Сохранить
                  </button>
                  <button
                    type="button"
                    className="rounded border border-red-200 px-4 py-2 text-sm text-red-600 disabled:opacity-50"
                    onClick={handleDeleteTable}
                    disabled={busy}
                  >
                    Удалить
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
