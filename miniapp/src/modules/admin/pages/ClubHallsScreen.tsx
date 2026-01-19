import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useUiStore } from '../../../shared/store/ui';
import {
  AdminApiError,
  AdminHall,
  createHall,
  deleteHall,
  isAbortError,
  listHalls,
  makeHallActive,
  updateHall,
} from '../api/admin.api';
import { mapAdminErrorMessage, mapValidationErrors, FieldErrors } from '../utils/adminErrors';

type ClubHallsScreenProps = {
  clubId: number;
  onBack: () => void;
};

type FormMode = 'create' | 'edit';

const emptyForm = {
  name: '',
  geometryJson: '',
  isActive: false,
};

export default function ClubHallsScreen({ clubId, onBack }: ClubHallsScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [halls, setHalls] = useState<AdminHall[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [formMode, setFormMode] = useState<FormMode>('create');
  const [form, setForm] = useState(emptyForm);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [editingHallId, setEditingHallId] = useState<number | null>(null);
  const [refreshIndex, setRefreshIndex] = useState(0);
  const requestIdRef = useRef(0);
  const mountedRef = useRef(false);

  const reload = useCallback(() => setRefreshIndex((value) => value + 1), []);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++requestIdRef.current;
    setStatus('loading');
    setErrorMessage(null);
    listHalls(clubId, controller.signal)
      .then((data) => {
        if (requestId !== requestIdRef.current) return;
        setHalls(data);
        setStatus('ready');
      })
      .catch((error: AdminApiError) => {
        if (requestId !== requestIdRef.current) return;
        if (isAbortError(error)) return;
        if (error.status === 403) {
          addToast(mapAdminErrorMessage(error));
          onBack();
          return;
        }
        const message = mapAdminErrorMessage(error);
        setErrorMessage(message);
        setStatus('error');
        addToast(message);
      });
    return () => controller.abort();
  }, [addToast, clubId, onBack, refreshIndex]);

  const handleEdit = useCallback((hall: AdminHall) => {
    setFormMode('edit');
    setEditingHallId(hall.id);
    setForm({ name: hall.name, geometryJson: '', isActive: hall.isActive });
    setFieldErrors({});
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, []);

  const handleReset = useCallback(() => {
    setFormMode('create');
    setEditingHallId(null);
    setForm(emptyForm);
    setFieldErrors({});
  }, []);

  const handleSubmit = useCallback(async () => {
    if (busy) return;
    setBusy(true);
    setFieldErrors({});
    try {
      if (formMode === 'edit' && editingHallId) {
        const payload: { name?: string; geometryJson?: string } = { name: form.name };
        if (form.geometryJson.trim().length > 0) {
          payload.geometryJson = form.geometryJson;
        }
        await updateHall(editingHallId, payload);
        if (!mountedRef.current) return;
        addToast('Зал обновлен');
      } else {
        await createHall(clubId, {
          name: form.name,
          geometryJson: form.geometryJson,
          isActive: form.isActive,
        });
        if (!mountedRef.current) return;
        addToast('Зал создан');
      }
      if (!mountedRef.current) return;
      handleReset();
      reload();
    } catch (error) {
      const normalized = error as AdminApiError;
      if (isAbortError(normalized)) return;
      if (normalized.status === 403) {
        addToast(mapAdminErrorMessage(normalized));
        return;
      }
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
  }, [addToast, busy, clubId, editingHallId, form, formMode, handleReset, reload]);

  const handleDelete = useCallback(
    async (hallId: number) => {
      if (busy) return;
      const confirmed = window.confirm('Удалить зал?');
      if (!confirmed) return;
      setBusy(true);
      try {
        await deleteHall(hallId);
        if (!mountedRef.current) return;
        addToast('Зал удален');
        reload();
      } catch (error) {
        const normalized = error as AdminApiError;
        if (isAbortError(normalized)) return;
        if (normalized.status === 403) {
          addToast(mapAdminErrorMessage(normalized));
          return;
        }
        if (!mountedRef.current) return;
        addToast(mapAdminErrorMessage(normalized));
      } finally {
        if (mountedRef.current) {
          setBusy(false);
        }
      }
    },
    [addToast, busy, reload],
  );

  const handleMakeActive = useCallback(
    async (hallId: number) => {
      if (busy) return;
      setBusy(true);
      try {
        await makeHallActive(hallId);
        if (!mountedRef.current) return;
        addToast('Зал активирован');
        reload();
      } catch (error) {
        const normalized = error as AdminApiError;
        if (isAbortError(normalized)) return;
        if (normalized.status === 403) {
          addToast(mapAdminErrorMessage(normalized));
          return;
        }
        if (!mountedRef.current) return;
        addToast(mapAdminErrorMessage(normalized));
      } finally {
        if (mountedRef.current) {
          setBusy(false);
        }
      }
    },
    [addToast, busy, reload],
  );

  const formTitle = useMemo(() => (formMode === 'edit' ? 'Редактировать зал' : 'Создать зал'), [formMode]);

  return (
    <div className="space-y-6 px-4 pb-8">
      <div className="flex items-center justify-between">
        <button type="button" className="text-sm text-blue-600" onClick={onBack} disabled={busy}>
          ← Клубы
        </button>
        <button type="button" className="text-sm text-blue-600" onClick={reload} disabled={busy}>
          Обновить
        </button>
      </div>

      <section className="rounded-lg bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">{formTitle}</h2>
          {formMode === 'edit' && (
            <button type="button" className="text-sm text-blue-600" onClick={handleReset} disabled={busy}>
              Сбросить
            </button>
          )}
        </div>
        <div className="mt-4 space-y-3">
          <div>
            <label htmlFor="hall-name" className="text-sm text-gray-600">
              Название
            </label>
            <input
              id="hall-name"
              className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
              value={form.name}
              onChange={(event) => setForm((prev) => ({ ...prev, name: event.target.value }))}
              disabled={busy}
            />
            {fieldErrors.name && <p className="mt-1 text-xs text-red-500">{fieldErrors.name}</p>}
          </div>
          <div>
            <label htmlFor="hall-geometry" className="text-sm text-gray-600">
              JSON схемы
            </label>
            <textarea
              id="hall-geometry"
              className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
              rows={4}
              value={form.geometryJson}
              onChange={(event) => setForm((prev) => ({ ...prev, geometryJson: event.target.value }))}
              disabled={busy}
              placeholder={formMode === 'edit' ? 'Оставьте пустым, если не хотите менять схему' : undefined}
            />
            {fieldErrors.geometryJson && <p className="mt-1 text-xs text-red-500">{fieldErrors.geometryJson}</p>}
          </div>
          {formMode === 'create' && (
            <label className="flex items-center gap-2 text-sm text-gray-600">
              <input
                type="checkbox"
                checked={form.isActive}
                onChange={(event) => setForm((prev) => ({ ...prev, isActive: event.target.checked }))}
                disabled={busy}
              />
              Сделать активным
            </label>
          )}
          {fieldErrors.payload && <p className="text-xs text-red-500">{fieldErrors.payload}</p>}
          <button
            type="button"
            className="mt-2 w-full rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            onClick={handleSubmit}
            disabled={busy}
          >
            {formMode === 'edit' ? 'Сохранить' : 'Создать'}
          </button>
        </div>
      </section>

      <section className="rounded-lg bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">Залы клуба #{clubId}</h2>
        </div>
        {status === 'loading' && <p className="mt-4 text-sm text-gray-500">Загрузка...</p>}
        {status === 'error' && (
          <div className="mt-4 space-y-2 text-sm text-gray-600">
            <p>{errorMessage ?? 'Не удалось загрузить залы'}</p>
            <button type="button" className="text-blue-600" onClick={reload} disabled={busy}>
              Повторить
            </button>
          </div>
        )}
        {status === 'ready' && halls.length === 0 && <p className="mt-4 text-sm text-gray-500">Залов нет</p>}
        {status === 'ready' && halls.length > 0 && (
          <div className="mt-4 space-y-3">
            {halls.map((hall) => (
              <div
                key={hall.id}
                className={`rounded border p-3 ${
                  hall.isActive ? 'border-green-200 bg-green-50' : 'border-gray-100'
                }`}
              >
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-gray-900">{hall.name}</p>
                    <p className="text-xs text-gray-500">Ревизия: {hall.layoutRevision}</p>
                  </div>
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs ${
                      hall.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {hall.isActive ? 'Активный' : 'Неактивный'}
                  </span>
                </div>
                <div className="mt-3 flex flex-wrap gap-2">
                  {!hall.isActive && (
                    <button
                      type="button"
                      className="rounded border border-blue-200 px-3 py-1 text-xs text-blue-600"
                      onClick={() => handleMakeActive(hall.id)}
                      disabled={busy}
                    >
                      Сделать активным
                    </button>
                  )}
                  <button
                    type="button"
                    className="rounded border border-gray-200 px-3 py-1 text-xs text-gray-700"
                    onClick={() => handleEdit(hall)}
                    disabled={busy}
                  >
                    Редактировать
                  </button>
                  <button
                    type="button"
                    className="rounded border border-red-200 px-3 py-1 text-xs text-red-600"
                    onClick={() => handleDelete(hall.id)}
                    disabled={busy}
                  >
                    Удалить
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
