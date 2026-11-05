import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useUiStore } from '../../../shared/store/ui';
import {
  AdminApiError,
  createHallTable,
  deleteHallTable,
  fetchHallPlanBlob,
  isAbortError,
  listHallTables,
  updateHallTable,
  uploadHallPlan,
} from '../api/admin.api';
import {
  FieldErrors,
  mapAdminErrorMessage,
  mapValidationErrors,
} from '../utils/adminErrors';
import ClubTableCreationForm, { TableCreation } from './ClubTableCreationForm';
import HallPlanStage from './HallPlanStage';
import HallPlanViewer from './HallPlanViewer';
import {
  EditorTable,
  HallEditorApi,
  LayoutId,
  PlanPoint,
} from './layoutEditor.types';
import { useUnsavedChanges } from '../../client/OnboardingShared';

const emptyForm = {
  tableNumber: '',
  label: '',
  capacity: '',
  minDeposit: '',
};

const maxFileSizeMb = 5;

type HallEditorScreenProps<Id extends LayoutId = number> = {
  clubId: Id;
  hallId: Id;
  api?: HallEditorApi<Id>;
  readOnly?: boolean;
  previewOnly?: boolean;
  clientMode?: boolean;
  reservedTableLocations?: Record<number, string>;
  reportDirty?: (dirty: boolean) => void;
  onBackReady?: (back: () => void) => void;
  onBack: () => void;
};

const adminEditorApi: HallEditorApi = {
  listHallTables,
  createHallTable,
  updateHallTable,
  deleteHallTable,
  uploadHallPlan,
  fetchHallPlanBlob,
};

export default function HallEditorScreen<Id extends LayoutId = number>({
  ...props
}: HallEditorScreenProps<Id>) {
  // A hall owns the entire editor lifetime, including URLs, requests, drafts and mutations.
  return (
    <HallEditorSession key={`${props.clubId}:${props.hallId}`} {...props} />
  );
}

function HallEditorSession<Id extends LayoutId = number>({
  clubId,
  hallId,
  onBack,
  api: suppliedApi,
  readOnly = false,
  previewOnly = false,
  clientMode = false,
  reservedTableLocations,
  reportDirty,
  onBackReady,
}: HallEditorScreenProps<Id>) {
  const api = suppliedApi ?? (adminEditorApi as unknown as HallEditorApi<Id>);
  const addToast = useUiStore((state) => state.addToast);
  const [planStatus, setPlanStatus] = useState<
    'loading' | 'ready' | 'empty' | 'error'
  >('loading');
  const [planError, setPlanError] = useState<string | null>(null);
  const [planUrl, setPlanUrl] = useState<string | null>(null);
  const planEtagRef = useRef<string | null>(null);
  const planUrlRef = useRef<string | null>(null);
  const [tablesStatus, setTablesStatus] = useState<
    'loading' | 'ready' | 'error'
  >('loading');
  const [tablesError, setTablesError] = useState<string | null>(null);
  const [tables, setTables] = useState<EditorTable<Id>[]>([]);
  const [selectedTableId, setSelectedTableId] = useState<Id | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [tableFinished, setTableFinished] = useState(false);
  const [creationOpen, setCreationOpen] = useState(false);
  const [creationError, setCreationError] = useState('');
  const [placePoint, setPlacePoint] = useState<PlanPoint | null>(null);
  const physical = api.physical;
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
      if (planUrlRef.current) {
        URL.revokeObjectURL(planUrlRef.current);
      }
    },
    [],
  );

  useEffect(() => {
    planUrlRef.current = planUrl;
  }, [planUrl]);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++requestIdPlanRef.current;
    setPlanStatus('loading');
    setPlanError(null);

    api
      .fetchHallPlanBlob(clubId, hallId, planEtagRef.current, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          !mountedRef.current ||
          requestId !== requestIdPlanRef.current
        )
          return;
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
        // Cleanup owns the URL immediately, including before React commits the next render.
        planUrlRef.current = nextUrl;
        setPlanUrl((prev) => {
          if (prev) URL.revokeObjectURL(prev);
          return nextUrl;
        });
        planEtagRef.current = result.etag ?? null;
        setPlanStatus('ready');
      })
      .catch((error: AdminApiError) => {
        if (
          controller.signal.aborted ||
          !mountedRef.current ||
          requestId !== requestIdPlanRef.current
        )
          return;
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
  }, [addToast, api, clubId, hallId, onBack, refreshIndex]);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++requestIdTablesRef.current;
    setTablesStatus('loading');
    setTablesError(null);

    api
      .listHallTables(hallId, controller.signal)
      .then((data) => {
        if (
          controller.signal.aborted ||
          !mountedRef.current ||
          requestId !== requestIdTablesRef.current
        )
          return;
        setTables(data);
        setTablesStatus('ready');
      })
      .catch((error: AdminApiError) => {
        if (
          controller.signal.aborted ||
          !mountedRef.current ||
          requestId !== requestIdTablesRef.current
        )
          return;
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
  }, [addToast, api, hallId, onBack, refreshIndex]);

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
    setTableFinished(false);
    setForm({
      tableNumber: selectedTable.tableNumber.toString(),
      label: selectedTable.label,
      capacity: selectedTable.capacity.toString(),
      minDeposit: selectedTable.bookingDeposit ?? '',
    });
    setFieldErrors({});
  }, [selectedTable]);

  const dirty = Boolean(
    selectedTable &&
      (form.label !== selectedTable.label ||
        form.capacity !== String(selectedTable.capacity) ||
        form.tableNumber !== String(selectedTable.tableNumber) ||
        (physical && form.minDeposit !== (selectedTable.bookingDeposit ?? ''))),
  );
  const leave = useUnsavedChanges(dirty || busy || creationOpen, reportDirty);

  const nextTableNumber = useMemo(() => {
    if (!tables.length) return 1;
    return Math.max(...tables.map((table) => table.tableNumber ?? 0)) + 1;
  }, [tables]);

  const handleUploadPlan = useCallback(
    async (file: File) => {
      if (busy || readOnly || !leave()) return;
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
        await api.uploadHallPlan(hallId, file);
        if (!mountedRef.current) return;
        addToast(
          clientMode
            ? 'План обновлён. Проверьте положение маркеров.'
            : 'План зала обновлен',
        );
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
    [addToast, api, busy, clientMode, hallId, leave, readOnly, reload],
  );

  const handleCreateTable = useCallback(
    async (coords: { x: number; y: number }) => {
      if (busy || previewMode || readOnly || !leave()) return;
      setBusy(true);
      setFieldErrors({});
      try {
        const created = await api.createHallTable(hallId, {
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
    [
      addToast,
      api,
      busy,
      hallId,
      leave,
      nextTableNumber,
      previewMode,
      readOnly,
    ],
  );

  const handleCreatePhysical = async (draft: TableCreation) => {
    if (!physical || busy || previewMode || readOnly) return;
    setBusy(true);
    setCreationError('');
    try {
      const number = draft.numbers[0];
      const created =
        draft.mode === 'single'
          ? [
              await physical.create(hallId, {
                tableNumber: number,
                capacity: draft.capacity,
                label: draft.label || `Стол ${number}`,
                ...(draft.minDeposit && { minDeposit: draft.minDeposit }),
              }),
            ]
          : await physical.createBatch(hallId, draft.numbers, draft.capacity);
      if (!mountedRef.current) return;
      setTables((previous) => [...previous, ...created]);
      setCreationOpen(false);
      addToast(
        created.length === 1
          ? 'Стол создан'
          : `Создано столов: ${created.length}`,
      );
    } catch (error) {
      if (mountedRef.current && !isAbortError(error)) {
        const normalized = error as AdminApiError;
        setCreationError(
          normalized.code === 'table_number_conflict'
            ? `Не удалось создать столы №${draft.numbers.join(', №')}: номер уже занят в клубе. Обновите данные и выберите свободные номера.`
            : mapAdminErrorMessage(normalized),
        );
      }
    } finally {
      if (mountedRef.current) setBusy(false);
    }
  };

  const applyPlacement = async (id: Id, point: PlanPoint | null) => {
    if (!physical || busy || previewMode || readOnly) return;
    setBusy(true);
    try {
      const updated = await physical.place(hallId, id, point);
      if (!mountedRef.current) return;
      setTables((previous) =>
        previous.map((table) => (table.id === id ? updated : table)),
      );
      setPlacePoint(null);
      setSelectedTableId(point ? id : null);
      addToast(point ? 'Стол размещён' : 'Маркер снят. Стол сохранён.');
    } catch (error) {
      if (mountedRef.current && !isAbortError(error)) {
        addToast(mapAdminErrorMessage(error as AdminApiError));
      }
    } finally {
      if (mountedRef.current) setBusy(false);
    }
  };

  const handleMoveTable = useCallback(
    async (tableId: Id, coords: { x: number; y: number }) => {
      if (busy || previewMode || readOnly || !leave()) return;
      setBusy(true);
      setTables((prev) =>
        prev.map((table) =>
          table.id === tableId ? { ...table, ...coords } : table,
        ),
      );
      try {
        const updated = await api.updateHallTable(hallId, tableId, {
          x: coords.x,
          y: coords.y,
        });
        if (!mountedRef.current) return;
        setTables((prev) =>
          prev.map((table) => (table.id === updated.id ? updated : table)),
        );
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
    [addToast, api, busy, hallId, leave, previewMode, readOnly, reload],
  );

  const finishTable = useCallback(() => {
    setSelectedTableId(null);
    setFieldErrors({});
    setTableFinished(true);
  }, []);

  const handleFinishTable = useCallback(async () => {
    if (!selectedTable || busy || previewMode || readOnly) return;
    if (!dirty) {
      finishTable();
      return;
    }
    setBusy(true);
    setFieldErrors({});
    try {
      const payload = {
        label: form.label.trim(),
        capacity: Number(form.capacity),
        tableNumber: Number(form.tableNumber),
      };
      const updated = physical
        ? await physical.update(hallId, selectedTable.id, {
            ...payload,
            ...(selectedTable.bookingLinked &&
            form.minDeposit !== (selectedTable.bookingDeposit ?? '')
              ? { minDeposit: form.minDeposit || '0' }
              : {}),
          })
        : await api.updateHallTable(hallId, selectedTable.id, payload);
      if (!mountedRef.current) return;
      setTables((prev) =>
        prev.map((table) => (table.id === updated.id ? updated : table)),
      );
      finishTable();
    } catch (error) {
      const normalized = error as AdminApiError;
      if (isAbortError(normalized)) return;
      const validation = mapValidationErrors(normalized);
      if (validation) {
        if (!mountedRef.current) return;
        setFieldErrors(validation);
      } else {
        if (!mountedRef.current) return;
        setFieldErrors({ payload: mapAdminErrorMessage(normalized) });
      }
    } finally {
      if (mountedRef.current) {
        setBusy(false);
      }
    }
  }, [
    api,
    busy,
    dirty,
    finishTable,
    form,
    hallId,
    physical,
    previewMode,
    readOnly,
    selectedTable,
  ]);

  const handleDeleteTable = useCallback(async () => {
    if (!selectedTable || busy || previewMode || readOnly) return;
    const confirmed = window.confirm('Удалить стол?');
    if (!confirmed) return;
    setBusy(true);
    try {
      await api.deleteHallTable?.(hallId, selectedTable.id);
      if (!mountedRef.current) return;
      setTables((prev) =>
        prev.filter((table) => table.id !== selectedTable.id),
      );
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
  }, [addToast, api, busy, hallId, previewMode, readOnly, selectedTable]);

  const back = useCallback(() => {
    if (leave()) onBack();
  }, [leave, onBack]);
  useEffect(() => {
    onBackReady?.(back);
  }, [back, onBackReady]);
  const isReadOnly = previewMode || busy || readOnly;
  const showPreview = previewMode || previewOnly;

  const unplaced = tables.filter(
    (table) => table.x === null || table.y === null,
  );
  const tableList = (
    <section className="rounded-lg bg-white p-4 shadow-sm">
      <h2 className="text-base font-semibold text-gray-900">
        {clientMode ? 'Список столов' : 'Столы'}
      </h2>
      {physical &&
        (creationOpen || !readOnly) &&
        tablesStatus === 'ready' &&
        (creationOpen ? (
          <div className="mt-3 space-y-2">
            <ClubTableCreationForm
              locations={reservedTableLocations}
              reserved={physical.reservedNumbers()}
              busy={busy || readOnly}
              onSave={(draft) => void handleCreatePhysical(draft)}
              onClose={() => {
                setCreationError('');
                setCreationOpen(false);
              }}
            />
            {creationError && (
              <p role="alert" className="text-sm text-red-700">
                {creationError}
              </p>
            )}
          </div>
        ) : (
          <button
            type="button"
            className="mt-3 min-h-[44px] rounded border border-blue-200 px-3 text-blue-700"
            disabled={busy}
            onClick={() => {
              if (!leave()) return;
              setSelectedTableId(null);
              setPlacePoint(null);
              setCreationOpen(true);
            }}
          >
            Добавить столы
          </button>
        ))}
      {tablesStatus === 'loading' && (
        <p className="mt-4 text-sm text-gray-500">Загрузка...</p>
      )}
      {tablesStatus === 'error' && (
        <div className="mt-4 space-y-2 text-sm text-gray-600">
          <p>{tablesError ?? 'Не удалось загрузить столы'}</p>
          <button
            type="button"
            className="text-blue-600"
            onClick={() => {
              if (leave()) reload();
            }}
            disabled={busy}
          >
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
              aria-label={`#${table.tableNumber} · ${table.label}`}
              aria-describedby={
                physical && !table.bookingLinked
                  ? `table-link-${table.id}`
                  : undefined
              }
              className={`rounded border px-3 py-1 text-xs ${
                table.id === selectedTableId
                  ? 'border-blue-500 bg-blue-50 text-blue-600'
                  : 'border-gray-200 text-gray-600'
              }`}
              onClick={() => {
                if (!leave()) return;
                setCreationOpen(false);
                setPlacePoint(null);
                setSelectedTableId(table.id);
              }}
              disabled={busy}
            >
              #{table.tableNumber} · {table.label}
              {physical && !table.bookingLinked && (
                <small id={`table-link-${table.id}`} className="block">
                  Требует подключения
                </small>
              )}
            </button>
          ))}
        </div>
      )}
      <div className="mt-6 space-y-3">
        {selectedTable && (
          <p className="text-sm font-medium text-gray-800">Выбранный стол</p>
        )}
        {!selectedTable && (
          <p role="status" className="text-sm text-gray-700">
            {tableFinished
              ? planStatus === 'ready'
                ? physical
                  ? 'Готово. Выберите стол в списке или на схеме.'
                  : 'Готово. Нажмите на свободное место схемы, чтобы добавить следующий стол.'
                : 'Готово.'
              : physical
                ? 'Выберите стол в списке или на схеме.'
                : 'Выберите стол на плане.'}
          </p>
        )}
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
                onChange={(event) =>
                  setForm((prev) => ({
                    ...prev,
                    tableNumber: event.target.value,
                  }))
                }
                disabled={isReadOnly}
              />
              {fieldErrors.tableNumber && (
                <p className="mt-1 text-xs text-red-500">
                  {fieldErrors.tableNumber}
                </p>
              )}
            </div>
            <div>
              <label htmlFor="table-label" className="text-sm text-gray-600">
                Название
              </label>
              <input
                id="table-label"
                className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
                value={form.label}
                onChange={(event) =>
                  setForm((prev) => ({
                    ...prev,
                    label: event.target.value,
                  }))
                }
                disabled={isReadOnly}
              />
              {fieldErrors.label && (
                <p className="mt-1 text-xs text-red-500">{fieldErrors.label}</p>
              )}
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
                onChange={(event) =>
                  setForm((prev) => ({
                    ...prev,
                    capacity: event.target.value,
                  }))
                }
                disabled={isReadOnly}
              />
              {fieldErrors.capacity && (
                <p className="mt-1 text-xs text-red-500">
                  {fieldErrors.capacity}
                </p>
              )}
            </div>
            {physical && selectedTable.bookingLinked && (
              <label className="block text-sm text-gray-600">
                Минимальный депозит в единицах booking
                <input
                  inputMode="decimal"
                  className="mt-1 block min-h-[44px] w-full rounded border border-gray-300 px-3 text-gray-900"
                  value={form.minDeposit}
                  disabled={isReadOnly}
                  onChange={(event) =>
                    setForm((previous) => ({
                      ...previous,
                      minDeposit: event.target.value,
                    }))
                  }
                />
              </label>
            )}
            {fieldErrors.payload && (
              <p role="alert" className="text-sm text-red-700">
                {fieldErrors.payload}
              </p>
            )}
            {!previewMode && !readOnly && (
              <div className="flex gap-2">
                <button
                  type="button"
                  className="min-h-[44px] flex-1 rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                  onClick={handleFinishTable}
                  disabled={busy}
                >
                  {dirty ? 'Сохранить и завершить' : 'Готово'}
                </button>
                {physical &&
                  selectedTable.x !== null &&
                  selectedTable.y !== null && (
                    <button
                      type="button"
                      className="min-h-[44px] rounded border border-gray-300 px-3 text-sm"
                      disabled={busy}
                      onClick={() => {
                        if (leave())
                          void applyPlacement(selectedTable.id, null);
                      }}
                    >
                      Убрать со схемы
                    </button>
                  )}
                {api.deleteHallTable && (
                  <button
                    type="button"
                    className="rounded border border-red-200 px-4 py-2 text-sm text-red-600 disabled:opacity-50"
                    onClick={handleDeleteTable}
                    disabled={busy}
                  >
                    Удалить
                  </button>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );

  return (
    <div className="space-y-6 px-4 pb-8">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {!clientMode && (
          <button
            type="button"
            className="text-sm text-blue-600"
            onClick={back}
            disabled={busy}
          >
            ← Залы
          </button>
        )}
        <div className="flex flex-wrap items-center gap-2">
          {!readOnly && (
            <button
              type="button"
              className="ob-button"
              onClick={() => {
                if (!leave()) return;
                if (selectedTable) {
                  setForm({
                    tableNumber: String(selectedTable.tableNumber),
                    label: selectedTable.label,
                    capacity: String(selectedTable.capacity),
                    minDeposit: selectedTable.bookingDeposit ?? '',
                  });
                  setFieldErrors({});
                }
                setPreviewMode((value) => !value);
              }}
              disabled={busy}
            >
              {previewMode
                ? 'Вернуться к редактированию'
                : 'Предпросмотр для гостя'}
            </button>
          )}
          <button
            type="button"
            className="text-sm text-blue-600"
            onClick={() => {
              if (leave()) reload();
            }}
            disabled={busy}
          >
            Обновить
          </button>
        </div>
      </div>

      {clientMode &&
        !previewMode &&
        (!previewOnly || planStatus !== 'ready') &&
        tableList}
      <section className="rounded-lg bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">План зала</h2>
        </div>
        {physical && tablesStatus === 'ready' && (
          <div className="mt-2 space-y-1 text-sm text-gray-700">
            <p>
              На схеме: {tables.length - unplaced.length} из {tables.length}
            </p>
            <p>Неразмещённые столы</p>
            {unplaced.length ? (
              <ul className="flex flex-wrap gap-2">
                {unplaced.map((table) => (
                  <li key={table.id}>№{table.tableNumber}</li>
                ))}
              </ul>
            ) : (
              <p>Нет неразмещённых столов.</p>
            )}
          </div>
        )}
        {planStatus === 'loading' && (
          <p className="mt-4 text-sm text-gray-500">Загрузка плана...</p>
        )}
        {planStatus === 'error' && (
          <div className="mt-4 space-y-2 text-sm text-gray-600">
            <p>{planError ?? 'Не удалось загрузить план'}</p>
            <button
              type="button"
              className="text-blue-600"
              onClick={() => {
                if (leave()) reload();
              }}
              disabled={busy}
            >
              Повторить
            </button>
          </div>
        )}
        {planStatus === 'empty' && (
          <div className="mt-4 space-y-3 text-sm text-gray-600">
            <p>План еще не загружен.</p>
            {!readOnly && (
              <label className="flex flex-col gap-2">
                <span className="text-xs text-gray-500">
                  PNG/JPEG, до 5 МБ.
                </span>
                <input
                  type="file"
                  accept="image/png,image/jpeg"
                  disabled={isReadOnly}
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
        {planStatus === 'ready' && planUrl && (
          <div className="mt-4 space-y-3">
            {!showPreview && !readOnly && physical && (
              <p className="text-sm text-gray-700">
                Коснитесь свободного места, чтобы выбрать неразмещённый стол.
                Перетащите маркер, чтобы переместить стол.
              </p>
            )}
            {!showPreview && !readOnly && !physical && (
              <div role="status" className="space-y-1 text-sm text-gray-700">
                <p className="font-medium">
                  {selectedTable
                    ? `Редактирование стола №${selectedTable.tableNumber}`
                    : 'Добавление нового стола'}
                </p>
                <p>
                  {selectedTable
                    ? 'Касание свободного места перемещает выбранный стол. Чтобы добавить другой стол, сначала завершите редактирование под параметрами.'
                    : 'Нажмите на свободное место схемы, чтобы добавить стол.'}
                </p>
              </div>
            )}
            {showPreview ? (
              tablesStatus === 'ready' ? (
                <HallPlanViewer planUrl={planUrl} tables={tables} />
              ) : tablesStatus === 'loading' ? (
                <p className="text-sm text-gray-500" role="status">
                  Загрузка столов…
                </p>
              ) : (
                <div className="space-y-2 text-sm text-gray-600" role="alert">
                  <p>{tablesError ?? 'Не удалось загрузить столы'}</p>
                  <button
                    type="button"
                    className="text-blue-600"
                    onClick={reload}
                  >
                    Повторить загрузку столов
                  </button>
                </div>
              )
            ) : (
              <HallPlanStage
                key={planUrl}
                planUrl={planUrl}
                tables={tables}
                selectedTableId={selectedTableId}
                readOnly={isReadOnly}
                onSelectTable={(tableId) => {
                  if (tableId !== selectedTableId && !leave()) return false;
                  setCreationOpen(false);
                  setPlacePoint(null);
                  setSelectedTableId(tableId);
                  return true;
                }}
                moveOnEmptyClick={!clientMode}
                onCreateTable={
                  clientMode
                    ? (point) => {
                        if (!physical || isReadOnly || !leave()) return;
                        setCreationOpen(false);
                        setPlacePoint(point);
                      }
                    : handleCreateTable
                }
                onMoveTable={handleMoveTable}
              />
            )}
            {physical && placePoint && !showPreview && !readOnly && (
              <section
                aria-label="Выбор неразмещённого стола"
                className="space-y-2 rounded border p-3 text-sm"
              >
                <h3 className="font-semibold">Выберите неразмещённый стол</h3>
                {unplaced.length === 0 && <p>Все столы уже размещены.</p>}
                <div className="flex flex-wrap gap-2">
                  {unplaced.map((table) => (
                    <button
                      key={table.id}
                      type="button"
                      className="min-h-[44px] rounded border px-3"
                      disabled={busy}
                      onClick={() => void applyPlacement(table.id, placePoint)}
                    >
                      Разместить №{table.tableNumber} · {table.label}
                    </button>
                  ))}
                </div>
                <button
                  type="button"
                  className="min-h-[44px]"
                  disabled={busy}
                  onClick={() => setPlacePoint(null)}
                >
                  Отмена размещения
                </button>
              </section>
            )}
            {!previewMode && !readOnly && (
              <label className="flex flex-col gap-2 text-sm text-gray-600">
                <span className="text-xs text-gray-500">
                  Обновить план (PNG/JPEG, до 5 МБ).
                  {physical &&
                    ' После замены проверьте положение сохранённых маркеров.'}
                </span>
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

      {!clientMode && !showPreview && tableList}
    </div>
  );
}
