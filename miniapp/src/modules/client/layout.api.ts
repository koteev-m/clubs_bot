import { AdminApiError } from '../admin/api/admin.api';
import {
  ClubHallsApi,
  EditorHall,
  HallEditorApi,
} from '../admin/pages/layoutEditor.types';

export interface ClientLayoutTable {
  id: string;
  hallId: string;
  tableNumber: number;
  label: string;
  capacity: number;
  bookingDeposit?: string | null;
  bookingLinked?: boolean;
  x: number | null;
  y: number | null;
}
export interface ClientLayoutHall {
  id: string;
  name: string;
  layoutRevision: string;
  tableCount?: number;
  tables: ClientLayoutTable[];
  plan: null | { contentType: string; sha256: string; sizeBytes: number };
}
export interface ClientLayout {
  id: string;
  clubId: string;
  clientId: string;
  permissions: string[];
  reservedTableNumbers?: number[];
  halls: ClientLayoutHall[];
}
interface Pending {
  path: string;
  method: string;
  body: string | File;
  headers: Record<string, string>;
}
export interface LayoutState {
  layout?: ClientLayout;
  pending: boolean;
  saving: boolean;
  error?: AdminApiError;
}

/** One cabinet lifetime, one club, opaque BIGINT IDs, and one retained mutation intent. */
export function createClientLayoutApi(
  credential: string,
  clubId: string,
  changed: (state: LayoutState) => void,
  lost: () => void,
) {
  const base = '/api/client/clubs/' + clubId;
  const layoutPath = base + '/layout';
  const lifetime = new AbortController();
  let current: ClientLayout | undefined;
  let pending: Pending | undefined;
  let saving = false;
  let generation = 0;
  let denied = false;
  let readSequence = 0;
  let acceptedRead = 0;
  let observedLayout: ClientLayout | undefined;
  let conflict: AdminApiError | undefined;
  const revisions = (snapshot: ClientLayout) =>
    JSON.stringify(
      snapshot.halls.map(({ id, layoutRevision }) => [id, layoutRevision]),
    );
  const versionConflict = () =>
    new AdminApiError(
      'Схема изменена. Загрузите актуальные данные перед сохранением.',
      { status: 409, code: 'version_conflict' },
    );
  const publish = (error?: AdminApiError) => {
    if (!lifetime.signal.aborted)
      changed({
        layout: current,
        pending: Boolean(pending),
        saving,
        error: conflict ?? error,
      });
  };
  const deny = () => {
    if (lifetime.signal.aborted || denied) return;
    denied = true;
    generation += 1;
    current = undefined;
    pending = undefined;
    saving = false;
    conflict = undefined;
    publish(
      new AdminApiError('Доступ утрачен или запись недоступна.', {
        status: 404,
        code: 'not_found',
      }),
    );
    lost();
    lifetime.abort();
  };
  const request = async (
    path: string,
    options: RequestInit = {},
    external?: AbortSignal,
    allowMissingPlan = false,
  ): Promise<Response> => {
    if (denied || lifetime.signal.aborted)
      throw new AdminApiError('Доступ утрачен.', {
        status: 404,
        code: 'not_found',
        isAbort: lifetime.signal.aborted,
      });
    const controller = new AbortController();
    const abort = () => controller.abort();
    lifetime.signal.addEventListener('abort', abort, { once: true });
    external?.addEventListener('abort', abort, { once: true });
    if (lifetime.signal.aborted || external?.aborted) controller.abort();
    const timeout = window.setTimeout(abort, 15000);
    try {
      const response = await fetch(path, {
        ...options,
        headers: { 'X-Telegram-Init-Data': credential, ...options.headers },
        signal: controller.signal,
        cache: 'no-store',
        credentials: 'same-origin',
        referrerPolicy: 'no-referrer',
      });
      if (!response.ok) {
        if (allowMissingPlan && response.status === 404) return response;
        const data = await response.json().catch(() => null);
        if ([401, 403, 404].includes(response.status)) deny();
        throw new AdminApiError('Не удалось выполнить действие.', {
          status: response.status,
          code: typeof data?.code === 'string' ? data.code : 'request_failed',
          details: data?.details,
        });
      }
      return response;
    } catch (error) {
      if (error instanceof AdminApiError) throw error;
      throw new AdminApiError('Ответ не получен. Повторите ту же операцию.', {
        status: 0,
        code: 'unknown_outcome',
        isAbort: lifetime.signal.aborted || external?.aborted,
      });
    } finally {
      window.clearTimeout(timeout);
      lifetime.signal.removeEventListener('abort', abort);
      external?.removeEventListener('abort', abort);
    }
  };
  const validate = (layout: ClientLayout) => {
    if (denied || lifetime.signal.aborted)
      throw new AdminApiError('Запрос отменён', { isAbort: true });
    // A response cannot retarget the adapter to a different client/club.
    if (
      layout.clubId !== clubId ||
      !layout.permissions.includes('client.club.read')
    ) {
      deny();
      throw new AdminApiError('Доступ утрачен.', {
        status: 404,
        code: 'not_found',
      });
    }
  };
  const accept = (layout: ClientLayout) => {
    validate(layout);
    current = layout;
    conflict = undefined;
    publish();
    return layout;
  };
  const load = async (signal?: AbortSignal, adopt = true) => {
    const started = generation;
    const sequence = ++readSequence;
    let layout: ClientLayout;
    try {
      layout = (await (
        await request(layoutPath, {}, signal)
      ).json()) as ClientLayout;
    } catch (error) {
      if (
        !signal?.aborted &&
        started === generation &&
        sequence >= acceptedRead
      )
        publish(error as AdminApiError);
      throw error;
    }
    if (signal?.aborted)
      throw new AdminApiError('Запрос отменён', { isAbort: true });
    validate(layout);
    if (current && started !== generation) return current;
    if (current && sequence < acceptedRead)
      return adopt && !pending && observedLayout
        ? accept(observedLayout)
        : current;
    acceptedRead = sequence;
    observedLayout = layout;
    if (!current || (adopt && !pending)) return accept(layout);
    if (!pending && revisions(current) !== revisions(layout))
      conflict ??= versionConflict();
    current = { ...current, permissions: layout.permissions };
    publish();
    return current;
  };
  const hall = (id: string) => {
    const value = current?.halls.find((item) => item.id === id);
    if (!value)
      throw new AdminApiError('Зал недоступен.', {
        status: 404,
        code: 'not_found',
      });
    return value;
  };
  const execute = async (command: Pending) => {
    if (saving) throw new AdminApiError('Дождитесь завершения операции.');
    saving = true;
    generation += 1;
    publish();
    try {
      const result = await request(command.path, {
        method: command.method,
        headers: command.headers,
        body: command.body,
      });
      const layout = (await result.json()) as ClientLayout;
      pending = undefined;
      saving = false;
      generation += 1;
      return accept(layout);
    } catch (error) {
      const normalized = error as AdminApiError;
      saving = false;
      if (normalized.status && normalized.status < 500) pending = undefined;
      if (normalized.code === 'version_conflict') conflict = normalized;
      publish(normalized);
      throw normalized;
    }
  };
  const mutate = (
    path: string,
    body: object | File,
    method = 'POST',
    version?: string,
  ) => {
    if (pending || saving)
      throw new AdminApiError('Сначала повторите незавершённую операцию.');
    if (!current?.permissions.includes('client.club.layout.write'))
      throw new AdminApiError('Недостаточно прав.', {
        status: 403,
        code: 'forbidden',
      });
    if (conflict) throw conflict;
    const file = body instanceof File;
    pending = {
      path: base + path,
      method,
      body: file ? body : JSON.stringify(body),
      headers: {
        'Content-Type': file ? body.type : 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...(version ? { 'X-Layout-Version': version } : {}),
      },
    };
    return execute(pending);
  };
  const editorHall = (value: ClientLayoutHall): EditorHall<string> => ({
    id: value.id,
    clubId,
    name: value.name,
    layoutRevision: value.layoutRevision,
    tableCount: value.tableCount ?? value.tables.length,
    isActive: true,
  });
  const halls: ClubHallsApi<string> = {
    async listHalls(_id, signal) {
      return (await load(signal, false)).halls.map(editorHall);
    },
    async createHall(_id, fields) {
      const previous = new Set(current?.halls.map((item) => item.id));
      const result = await mutate('/halls', { name: fields.name });
      const created = result.halls.find((item) => !previous.has(item.id));
      if (!created) throw new AdminApiError('Обновите список залов.');
      return editorHall(created);
    },
    async updateHall(id, fields) {
      const result = await mutate(
        '/halls/' + id,
        { name: fields.name, expectedVersion: hall(id).layoutRevision },
        'PATCH',
      );
      return editorHall(result.halls.find((item) => item.id === id)!);
    },
  };
  const createTables = async (id: string, suffix: string, fields: object) => {
    const previous = new Set(hall(id).tables.map((table) => table.id));
    const result = await mutate('/halls/' + id + '/tables' + suffix, {
      ...fields,
      expectedVersion: hall(id).layoutRevision,
    });
    return (
      result.halls
        .find((item) => item.id === id)
        ?.tables.filter((table) => !previous.has(table.id)) ?? []
    );
  };
  const createOne = async (id: string, fields: object) => {
    const created = (await createTables(id, '', fields))[0];
    if (!created) throw new AdminApiError('Обновите список столов.');
    return created;
  };
  const patchTable = async (id: string, tableId: string, fields: object) => {
    const result = await mutate(
      '/halls/' + id + '/tables/' + tableId,
      { ...fields, expectedVersion: hall(id).layoutRevision },
      'PATCH',
    );
    const updated = result.halls
      .find((item) => item.id === id)
      ?.tables.find((item) => item.id === tableId);
    if (!updated) throw new AdminApiError('Обновите список столов.');
    return updated;
  };
  const editor: HallEditorApi<string> = {
    async listHallTables(id, signal) {
      await load(signal, false);
      return hall(id).tables;
    },
    createHallTable: createOne,
    updateHallTable: patchTable,
    physical: {
      reservedNumbers: () =>
        current?.reservedTableNumbers ??
        current?.halls.flatMap((item) =>
          item.tables.map((table) => table.tableNumber),
        ) ??
        [],
      create: createOne,
      createBatch: (id, tableNumbers, capacity) =>
        createTables(id, '/batch', { tableNumbers, capacity }),
      update: patchTable,
      place: (id, table, point) =>
        patchTable(id, table, point ?? { x: null, y: null }),
    },
    async uploadHallPlan(id, file) {
      return mutate(
        '/halls/' + id + '/plan',
        file,
        'POST',
        hall(id).layoutRevision,
      );
    },
    async removeHallPlan(id) {
      return mutate('/halls/' + id + '/plan/remove', {
        expectedVersion: hall(id).layoutRevision,
      });
    },
    async fetchHallPlanBlob(_club, id, _etag, signal) {
      // Metadata distinguishes an empty plan from a refused resource; a binary 404 always clears the scene.
      await load(signal, false);
      if (!hall(id).plan) return { status: 404 };
      const started = generation;
      const response = await request(
        base + '/halls/' + id + '/plan',
        {},
        signal,
        true,
      );
      if (response.status === 404) {
        // The plan can disappear between the fresh metadata and image requests.
        // Confirm absence through a new authorized, same-hall metadata response.
        try {
          const latest = (await (
            await request(layoutPath, {}, signal)
          ).json()) as ClientLayout;
          if (signal?.aborted)
            throw new AdminApiError('Запрос отменён', { isAbort: true });
          validate(latest);
          if (latest.halls.find((item) => item.id === id)?.plan === null) {
            if (started === generation) {
              conflict ??= versionConflict();
              publish();
            }
            return { status: 404 };
          }
        } catch (error) {
          if (signal?.aborted || lifetime.signal.aborted) throw error;
        }
        deny();
        throw new AdminApiError('Изображение недоступно.', {
          status: 404,
          code: 'not_found',
        });
      }
      const blob = await response.blob();
      if (denied || lifetime.signal.aborted || signal?.aborted)
        throw new AdminApiError('Запрос отменён', { isAbort: true });
      return { status: 200, blob };
    },
  };
  return {
    halls,
    editor,
    load,
    async revalidate() {
      await load(undefined, false);
    },
    async retry() {
      if (pending && !saving) return execute(pending);
    },
    dispose() {
      lifetime.abort();
      current = undefined;
      pending = undefined;
    },
  };
}
