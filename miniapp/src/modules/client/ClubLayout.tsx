import { useCallback, useEffect, useRef, useState } from 'react';
import ClubHallsScreen from '../admin/pages/ClubHallsScreen';
import HallEditorScreen from '../admin/pages/HallEditorScreen';
import { mapAdminErrorMessage } from '../admin/utils/adminErrors';
import { createClientLayoutApi, LayoutState } from './layout.api';
import {
  Shell,
  useTelegramClosingConfirmation,
  useUnsavedChanges,
} from './OnboardingShared';

export default function ClubLayout({
  credential,
  id,
  hallId,
  back,
  openHall,
  reportDirty,
  reportPending,
}: {
  credential: string;
  id: string;
  hallId?: string;
  back: () => void;
  openHall: (hall: string) => void;
  reportDirty: (dirty: boolean) => void;
  reportPending: (pending: boolean) => void;
}) {
  const [state, setState] = useState<LayoutState>({
    pending: false,
    saving: false,
  });
  const [denied, setDenied] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [dirty, setDirty] = useState(false);
  const childBack = useRef<(() => void) | null>(null);
  const navigation = useRef({ back, openHall });
  navigation.current = { back, openHall };
  const sectionBack = useCallback(() => navigation.current.back(), []);
  const openEditor = useCallback(
    (hall: string) => navigation.current.openHall(hall),
    [],
  );
  const [api, setApi] = useState<ReturnType<typeof createClientLayoutApi>>();
  useEffect(() => {
    reportPending(state.saving);
    return () => reportPending(false);
  }, [state.saving, reportPending]);
  const leave = useUnsavedChanges(
    dirty || state.pending || state.saving,
    reportDirty,
  );
  useTelegramClosingConfirmation(
    !denied && (dirty || state.pending || state.saving),
  );
  const receiveBack = useCallback((handler: (() => void) | null) => {
    childBack.current = handler;
  }, []);
  const reportChildDirty = useCallback((value: boolean) => setDirty(value), []);
  const goBack = () => {
    if (denied) {
      childBack.current = null;
      navigation.current.back();
      return;
    }
    if (state.saving) return;
    if (state.pending) {
      if (
        window.confirm(
          'Результат операции неизвестен. Перед уходом можно повторить её с тем же ключом. Всё равно уйти?',
        )
      )
        back();
      return;
    }
    if (childBack.current) childBack.current();
    else if (leave()) back();
  };
  useEffect(() => {
    const adapter = createClientLayoutApi(credential, id, setState, () =>
      setDenied(true),
    );
    setApi(adapter);
    void adapter.load().catch(() => undefined);
    return () => adapter.dispose();
  }, [credential, id]);
  useEffect(() => {
    if (!api) return;
    const foreground = () => {
      if (!document.hidden) void api.revalidate().catch(() => undefined);
    };
    window.addEventListener('focus', foreground);
    window.addEventListener('pageshow', foreground);
    document.addEventListener('visibilitychange', foreground);
    return () => {
      window.removeEventListener('focus', foreground);
      window.removeEventListener('pageshow', foreground);
      document.removeEventListener('visibilitychange', foreground);
    };
  }, [api]);
  const [refreshing, setRefreshing] = useState(false);
  const reload = async () => {
    if (!api || refreshing || state.pending || state.saving || !leave()) return;
    setRefreshing(true);
    try {
      await api.load();
      setRefresh((value) => value + 1);
    } catch {
      // The adapter has already published the error.
    } finally {
      setRefreshing(false);
    }
  };
  const canWrite = Boolean(
    state.layout?.permissions.includes('client.club.layout.write'),
  );
  const editBlocked = state.saving
    ? 'Сохранение… Дождитесь завершения операции.'
    : state.pending
      ? 'Сначала повторите незавершённую операцию.'
      : !canWrite
        ? 'У вас есть право только на просмотр.'
        : refreshing
          ? 'Загружаем актуальные данные.'
          : state.error?.code === 'version_conflict'
            ? 'Данные зала изменились. Загрузите актуальные данные перед редактированием.'
            : undefined;
  const selectedHall = state.layout?.halls.find((hall) => hall.id === hallId);
  const matchingPlans = selectedHall?.plan
    ? state.layout?.halls.filter(
        (hall) =>
          hall.id !== hallId && hall.plan?.sha256 === selectedHall.plan?.sha256,
      )
    : [];
  const tableLocations = Object.fromEntries(
    state.layout?.halls.flatMap((hall) =>
      hall.tables.map((table) => [table.tableNumber, hall.name]),
    ) ?? [],
  );
  if (!api) return null;
  return (
    <Shell
      title={selectedHall?.name ?? 'Залы и столы'}
      subtitle={hallId ? 'Схема и столы зала' : 'Настройка схемы заведения'}
      back={goBack}
    >
      {denied ? (
        <p className="po-notice" role="alert">
          Доступ утрачен или запись недоступна. Вернитесь в кабинет и обновите
          данные.
        </p>
      ) : (
        <>
          {state.error && (
            <p className="po-notice" role="alert">
              {mapAdminErrorMessage(state.error)}
            </p>
          )}
          {state.saving && (
            <p className="po-notice" role="status">
              {editBlocked}
            </p>
          )}
          {state.pending && !state.saving && (
            <div className="po-notice" role="alert">
              <p>
                Результат операции пока неизвестен. Повтор сохранит исходные
                данные и ключ команды.
              </p>
              <button
                className="ob-button"
                onClick={() => {
                  void api
                    .retry()
                    .then(() => setRefresh((value) => value + 1))
                    .catch(() => undefined);
                }}
              >
                Повторить ту же операцию
              </button>
            </div>
          )}
          {state.layout && (
            <div className="client-layout">
              {!state.pending && (
                <button
                  className="ob-button"
                  disabled={refreshing}
                  onClick={() => void reload()}
                >
                  {refreshing
                    ? 'Загружаем актуальные данные…'
                    : 'Загрузить актуальные данные'}
                </button>
              )}
              {Boolean(matchingPlans?.length) && (
                <p className="po-notice" role="status">
                  В сохранённых данных этого зала и{' '}
                  {matchingPlans?.map((hall) => `«${hall.name}»`).join(', ')}{' '}
                  одинаковое изображение. Схемы хранятся отдельно: загрузка
                  нового PNG/JPEG изменит только выбранный зал.
                </p>
              )}
              {!canWrite && (
                <p className="po-muted">
                  Залы и столы доступны только для просмотра.
                </p>
              )}
              {selectedHall ? (
                <HallEditorScreen
                  key={selectedHall.id + ':' + refresh}
                  clubId={id}
                  hallId={selectedHall.id}
                  api={api.editor}
                  clientMode
                  savedPlan={Boolean(selectedHall.plan)}
                  editBlockedReason={editBlocked}
                  reservedTableLocations={tableLocations}
                  previewOnly={!canWrite}
                  readOnly={Boolean(editBlocked)}
                  onBack={sectionBack}
                  onBackReady={receiveBack}
                  reportDirty={reportChildDirty}
                />
              ) : hallId ? (
                <p className="po-notice" role="alert">
                  Этот зал недоступен. Вернитесь к списку залов.
                </p>
              ) : (
                <ClubHallsScreen
                  key={id + ':' + refresh}
                  clubId={id}
                  api={api.halls}
                  clientMode
                  readOnly={Boolean(editBlocked)}
                  openDisabled={state.pending}
                  onBack={sectionBack}
                  onOpenEditor={openEditor}
                  onBackReady={receiveBack}
                  reportDirty={reportChildDirty}
                />
              )}
            </div>
          )}
          {!state.layout && !state.error && <p role="status">Загрузка…</p>}
          {!state.layout && state.error && (
            <button
              className="ob-button"
              onClick={() => {
                void api.load().catch(() => undefined);
              }}
            >
              Повторить загрузку
            </button>
          )}
        </>
      )}
      {denied && (
        <button className="ob-button" onClick={goBack}>
          {hallId ? 'Вернуться к списку залов' : 'Вернуться к клубу'}
        </button>
      )}
    </Shell>
  );
}
