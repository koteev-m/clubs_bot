import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminApiError } from '../admin/api/admin.api';
import { mapAdminErrorMessage } from '../admin/utils/adminErrors';
import {
  ClientLayoutHall,
  ClientLayoutTable,
  ClientLayoutZone,
  createClientLayoutApi,
} from './layout.api';

type ZoneApi = ReturnType<typeof createClientLayoutApi>['zones'];
const message = (error: unknown) => {
  const known: Record<string, string> = {
    zone_name_conflict: 'Зона с таким названием уже есть в этом зале.',
    zone_in_use:
      'В зоне остались столы, в том числе архивные. Сначала перенесите их в другую зону.',
    last_zone: 'В зале должна остаться хотя бы одна зона.',
    version_conflict:
      'Зал изменён. Загрузите актуальные данные и повторите изменение.',
  };
  return error instanceof AdminApiError
    ? (known[error.code ?? ''] ?? mapAdminErrorMessage(error))
    : 'Не удалось сохранить изменение.';
};

export default function ClubZones({
  hall,
  api,
  blocked,
  reportDirty,
}: {
  hall: ClientLayoutHall;
  api: ZoneApi;
  blocked: boolean;
  reportDirty: (dirty: boolean) => void;
}) {
  const [drafts, setDrafts] = useState<Record<string, boolean>>({});
  const dirty = useCallback((id: string, value: boolean) => {
    setDrafts((current) =>
      current[id] === value ? current : { ...current, [id]: value },
    );
  }, []);
  useEffect(() => {
    reportDirty(Object.values(drafts).some(Boolean));
    return () => reportDirty(false);
  }, [drafts, reportDirty]);
  if (!hall.zones) return null;
  return (
    <section className="ob-card" aria-label="Зоны зала">
      <h2>Зоны зала</h2>
      <p className="po-muted">
        Назовите зоны и распределите физические столы. Их номера, история
        бронирований и позиции на схеме сохраняются.
      </p>
      {hall.zones.map((zone) => (
        <ZoneName
          key={zone.id}
          hall={hall}
          zone={zone}
          api={api}
          blocked={blocked}
          dirty={dirty}
        />
      ))}
      <ZoneName hall={hall} api={api} blocked={blocked} dirty={dirty} />
      {hall.tables.length > 0 && <h3>Столы по зонам</h3>}
      {hall.tables.map((table) => (
        <TableZone
          key={table.id}
          hall={hall}
          table={table}
          api={api}
          blocked={blocked}
          dirty={dirty}
        />
      ))}
    </section>
  );
}

type Shared = {
  hall: ClientLayoutHall;
  api: ZoneApi;
  blocked: boolean;
  dirty: (id: string, value: boolean) => void;
};

type Draft = { value: string; original: string; version: string };

/** Server values stay live until this particular form starts its own versioned draft. */
function useZoneDraft(
  saved: string,
  version: string,
  id: string,
  dirty: Shared['dirty'],
) {
  const [draft, setDraft] = useState<Draft>();
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const current = useRef(draft);
  current.current = draft;
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    dirty(id, Boolean(draft));
    return () => dirty(id, false);
  }, [draft, dirty, id]);
  const replace = (next?: Draft) => {
    current.current = next;
    setDraft(next);
    setError('');
    setSuccess('');
  };
  const save = async (
    request: (
      value: string,
      expectedVersion: string,
      confirmed: () => void,
    ) => Promise<unknown>,
    label: string,
  ) => {
    const submitted = draft;
    if (!submitted) return;
    setError('');
    setSuccess('');
    const confirmed = () => {
      if (!mounted.current || current.current !== submitted) return;
      replace();
      setSuccess(label);
    };
    try {
      await request(submitted.value, submitted.version, confirmed);
      confirmed();
    } catch (failure) {
      if (mounted.current && current.current === submitted)
        setError(message(failure));
    }
  };
  return {
    value: draft?.value ?? saved,
    changed: Boolean(draft),
    stale: Boolean(draft && draft.version !== version),
    error,
    success,
    setError,
    save,
    cancel: () => replace(),
    change: (value: string) => {
      const base = draft ?? { original: saved, version, value: saved };
      replace(value === base.original ? undefined : { ...base, value });
    },
  };
}

function DraftFeedback({
  draft,
  blocked,
}: {
  draft: ReturnType<typeof useZoneDraft>;
  blocked: boolean;
}) {
  return (
    <>
      {draft.changed && (
        <button
          type="button"
          className="ob-button"
          disabled={blocked}
          onClick={draft.cancel}
        >
          Отменить изменения
        </button>
      )}
      {draft.stale && (
        <p className="po-muted">
          Зал изменился. Сверьте черновик с текущими данными; для новой правки
          отмените изменения.
        </p>
      )}
      {draft.error && (
        <p className="po-notice" role="alert">
          {draft.error}
        </p>
      )}
      {draft.success && <p role="status">{draft.success}</p>}
    </>
  );
}

function ZoneName({
  hall,
  zone,
  api,
  blocked,
  dirty,
}: Shared & { zone?: ClientLayoutZone }) {
  const draft = useZoneDraft(
    zone?.name ?? '',
    hall.layoutRevision,
    'zone:' + (zone?.id ?? 'new'),
    dirty,
  );
  const submit = () =>
    draft.save(
      (name, version, confirmed) =>
        zone
          ? api.update(hall.id, zone.id, name.trim(), version, confirmed)
          : api.create(hall.id, name.trim(), version, confirmed),
      'Зона сохранена.',
    );
  return (
    <form
      className="ob-card"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <label className="ob-field">
        {zone ? 'Название зоны «' + zone.name + '»' : 'Новая зона'}
        <input
          value={draft.value}
          maxLength={100}
          required
          disabled={blocked}
          onChange={(event) => draft.change(event.target.value)}
        />
      </label>
      {zone && <p className="po-muted">Столов в зоне: {zone.tableCount}</p>}
      <button
        className="ob-button"
        disabled={blocked || !draft.value.trim() || !draft.changed}
      >
        {zone ? 'Сохранить название зоны' : 'Добавить зону'}
      </button>
      {zone && (
        <>
          <button
            className="ob-button"
            type="button"
            disabled={blocked || zone.tableCount > 0 || hall.zones!.length < 2}
            onClick={async () => {
              draft.setError('');
              if (!window.confirm('Удалить пустую зону «' + zone.name + '»?'))
                return;
              try {
                await api.remove(hall.id, zone.id, hall.layoutRevision);
              } catch (failure) {
                draft.setError(message(failure));
              }
            }}
          >
            Удалить зону «{zone.name}»
          </button>
          {zone.tableCount > 0 && (
            <p className="po-muted">
              Удаление недоступно: в зоне есть столы. Перенесите их в другую
              зону; архивные столы также сохраняют связь.
            </p>
          )}
          {hall.zones!.length < 2 && (
            <p className="po-muted">
              В зале должна остаться хотя бы одна зона.
            </p>
          )}
        </>
      )}
      <DraftFeedback draft={draft} blocked={blocked} />
    </form>
  );
}

function TableZone({
  hall,
  table,
  api,
  blocked,
  dirty,
}: Shared & { table: ClientLayoutTable }) {
  const draft = useZoneDraft(
    table.zoneId ?? '',
    hall.layoutRevision,
    'table:' + table.id,
    dirty,
  );
  return (
    <form
      className="ob-card"
      onSubmit={async (event) => {
        event.preventDefault();
        await draft.save(
          (selection, version, confirmed) =>
            api.assign(hall.id, table.id, selection, version, confirmed),
          'Принадлежность стола сохранена.',
        );
      }}
    >
      <label className="ob-field">
        Зона стола {table.tableNumber} · {table.label}
        <select
          value={draft.value}
          disabled={blocked}
          onChange={(event) => draft.change(event.target.value)}
        >
          {!table.zoneId && <option value="">Требуется назначить зону</option>}
          {hall.zones!.map((zone) => (
            <option key={zone.id} value={zone.id}>
              {zone.name}
            </option>
          ))}
        </select>
      </label>
      <button
        className="ob-button"
        disabled={blocked || !draft.value || !draft.changed}
      >
        Сохранить зону стола {table.tableNumber}
      </button>
      <DraftFeedback draft={draft} blocked={blocked} />
    </form>
  );
}
