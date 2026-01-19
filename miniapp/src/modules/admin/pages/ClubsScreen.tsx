import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useUiStore } from '../../../shared/store/ui';
import {
  AdminApiError,
  AdminClub,
  createClub,
  deleteClub,
  isAbortError,
  listClubs,
  updateClub,
} from '../api/admin.api';
import { mapAdminErrorMessage, mapValidationErrors, FieldErrors } from '../utils/adminErrors';

type ClubsScreenProps = {
  onSelectClub: (clubId: number) => void;
  onForbidden: () => void;
};

type FormMode = 'create' | 'edit';

const emptyForm = {
  name: '',
  city: '',
  isActive: true,
};

export default function ClubsScreen({ onSelectClub, onForbidden }: ClubsScreenProps) {
  const { addToast } = useUiStore();
  const [clubs, setClubs] = useState<AdminClub[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [formMode, setFormMode] = useState<FormMode>('create');
  const [form, setForm] = useState(emptyForm);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [editingClubId, setEditingClubId] = useState<number | null>(null);
  const [refreshIndex, setRefreshIndex] = useState(0);
  const requestIdRef = useRef(0);

  const reload = useCallback(() => setRefreshIndex((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++requestIdRef.current;
    setStatus('loading');
    setErrorMessage(null);
    listClubs(controller.signal)
      .then((data) => {
        if (requestId !== requestIdRef.current) return;
        setClubs(data);
        setStatus('ready');
      })
      .catch((error: AdminApiError) => {
        if (requestId !== requestIdRef.current) return;
        if (isAbortError(error)) return;
        if (error.status === 403) {
          onForbidden();
          return;
        }
        const message = mapAdminErrorMessage(error);
        setErrorMessage(message);
        setStatus('error');
        addToast(message);
      });
    return () => controller.abort();
  }, [addToast, onForbidden, refreshIndex]);

  const handleEdit = useCallback((club: AdminClub) => {
    setFormMode('edit');
    setEditingClubId(club.id);
    setForm({ name: club.name, city: club.city, isActive: club.isActive });
    setFieldErrors({});
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, []);

  const handleReset = useCallback(() => {
    setFormMode('create');
    setEditingClubId(null);
    setForm(emptyForm);
    setFieldErrors({});
  }, []);

  const handleSubmit = useCallback(async () => {
    if (busy) return;
    setBusy(true);
    setFieldErrors({});
    try {
      if (formMode === 'edit' && editingClubId) {
        await updateClub(editingClubId, {
          name: form.name,
          city: form.city,
          isActive: form.isActive,
        });
        addToast('Клуб обновлен');
      } else {
        await createClub({ name: form.name, city: form.city, isActive: form.isActive });
        addToast('Клуб создан');
      }
      handleReset();
      reload();
    } catch (error) {
      const normalized = error as AdminApiError;
      if (isAbortError(normalized)) return;
      if (normalized.status === 403) {
        onForbidden();
        return;
      }
      const validation = mapValidationErrors(normalized);
      if (validation) {
        setFieldErrors(validation);
      } else {
        addToast(mapAdminErrorMessage(normalized));
      }
    } finally {
      setBusy(false);
    }
  }, [addToast, busy, editingClubId, form, formMode, handleReset, onForbidden, reload]);

  const handleDelete = useCallback(
    async (clubId: number) => {
      if (busy) return;
      const confirmed = window.confirm('Удалить клуб?');
      if (!confirmed) return;
      setBusy(true);
      try {
        await deleteClub(clubId);
        addToast('Клуб удален');
        reload();
      } catch (error) {
        const normalized = error as AdminApiError;
        if (isAbortError(normalized)) return;
        if (normalized.status === 403) {
          onForbidden();
          return;
        }
        addToast(mapAdminErrorMessage(normalized));
      } finally {
        setBusy(false);
      }
    },
    [addToast, busy, onForbidden, reload],
  );

  const formTitle = useMemo(() => (formMode === 'edit' ? 'Редактировать клуб' : 'Создать клуб'), [formMode]);

  return (
    <div className="space-y-6 px-4 pb-8">
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
            <label htmlFor="club-name" className="text-sm text-gray-600">
              Название
            </label>
            <input
              id="club-name"
              className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
              value={form.name}
              onChange={(event) => setForm((prev) => ({ ...prev, name: event.target.value }))}
              disabled={busy}
            />
            {fieldErrors.name && <p className="mt-1 text-xs text-red-500">{fieldErrors.name}</p>}
          </div>
          <div>
            <label htmlFor="club-city" className="text-sm text-gray-600">
              Город
            </label>
            <input
              id="club-city"
              className="mt-1 w-full rounded border border-gray-200 px-3 py-2 text-sm"
              value={form.city}
              onChange={(event) => setForm((prev) => ({ ...prev, city: event.target.value }))}
              disabled={busy}
            />
            {fieldErrors.city && <p className="mt-1 text-xs text-red-500">{fieldErrors.city}</p>}
          </div>
          <label className="flex items-center gap-2 text-sm text-gray-600">
            <input
              type="checkbox"
              checked={form.isActive}
              onChange={(event) => setForm((prev) => ({ ...prev, isActive: event.target.checked }))}
              disabled={busy}
            />
            Активен
          </label>
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
          <h2 className="text-base font-semibold text-gray-900">Клубы</h2>
          <button type="button" className="text-sm text-blue-600" onClick={reload} disabled={busy}>
            Обновить
          </button>
        </div>
        {status === 'loading' && <p className="mt-4 text-sm text-gray-500">Загрузка...</p>}
        {status === 'error' && (
          <div className="mt-4 space-y-2 text-sm text-gray-600">
            <p>{errorMessage ?? 'Не удалось загрузить клубы'}</p>
            <button type="button" className="text-blue-600" onClick={reload} disabled={busy}>
              Повторить
            </button>
          </div>
        )}
        {status === 'ready' && clubs.length === 0 && <p className="mt-4 text-sm text-gray-500">Клубов нет</p>}
        {status === 'ready' && clubs.length > 0 && (
          <div className="mt-4 space-y-3">
            {clubs.map((club) => (
              <div key={club.id} className="rounded border border-gray-100 p-3">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-gray-900">{club.name}</p>
                    <p className="text-xs text-gray-500">{club.city}</p>
                  </div>
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs ${
                      club.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {club.isActive ? 'Активен' : 'Неактивен'}
                  </span>
                </div>
                <div className="mt-3 flex flex-wrap gap-2">
                  <button
                    type="button"
                    className="rounded border border-gray-200 px-3 py-1 text-xs text-gray-700"
                    onClick={() => onSelectClub(club.id)}
                    disabled={busy}
                  >
                    Залы
                  </button>
                  <button
                    type="button"
                    className="rounded border border-gray-200 px-3 py-1 text-xs text-gray-700"
                    onClick={() => handleEdit(club)}
                    disabled={busy}
                  >
                    Редактировать
                  </button>
                  <button
                    type="button"
                    className="rounded border border-red-200 px-3 py-1 text-xs text-red-600"
                    onClick={() => handleDelete(club.id)}
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
