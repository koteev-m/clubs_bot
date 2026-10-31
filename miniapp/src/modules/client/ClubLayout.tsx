import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ClubHallsScreen from '../admin/pages/ClubHallsScreen';
import HallEditorScreen from '../admin/pages/HallEditorScreen';
import { mapAdminErrorMessage } from '../admin/utils/adminErrors';
import { createClientLayoutApi, LayoutState } from './layout.api';
import { Shell, useUnsavedChanges } from './OnboardingShared';

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
  openHall: (hall?: string) => void;
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
  const profileBack = useCallback(() => navigation.current.back(), []);
  const openEditor = useCallback(
    (hall?: string) => navigation.current.openHall(hall),
    [],
  );
  const api = useMemo(
    () =>
      createClientLayoutApi(credential, id, setState, () => setDenied(true)),
    [credential, id],
  );
  useEffect(() => {
    reportPending(state.pending || state.saving);
    return () => reportPending(false);
  }, [state.pending, state.saving, reportPending]);
  const leave = useUnsavedChanges(
    dirty || state.pending || state.saving,
    reportDirty,
  );
  const receiveBack = useCallback((handler: () => void) => {
    childBack.current = handler;
  }, []);
  const reportChildDirty = useCallback((value: boolean) => setDirty(value), []);
  const goBack = () => {
    if (denied) {
      childBack.current = null;
      navigation.current.back();
      return;
    }
    if (state.pending || state.saving) {
      // Keep the original idempotency key and intent available until its result is known.
      return;
    }
    if (childBack.current) childBack.current();
    else if (leave()) back();
  };
  useEffect(() => {
    void api.load().catch(() => undefined);
    return () => api.dispose();
  }, [api]);
  useEffect(() => {
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
  const editorBack = useCallback(() => navigation.current.openHall(), []);
  const reload = () => {
    if (state.pending || state.saving || !leave()) return;
    setRefresh((value) => value + 1);
  };
  const canWrite = Boolean(
    state.layout?.permissions.includes('client.club.layout.write'),
  );
  return (
    <Shell
      title="Залы и столы"
      subtitle={
        hallId
          ? state.layout?.halls.find((hall) => hall.id === hallId)?.name
          : 'Настройка схемы заведения'
      }
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
              {!canWrite && (
                <p className="po-muted">
                  Залы и столы доступны только для просмотра.
                </p>
              )}
              {hallId ? (
                <HallEditorScreen
                  key={hallId + ':' + refresh}
                  clubId={id}
                  hallId={hallId}
                  api={api.editor}
                  readOnly={
                    !canWrite || state.pending || state.error?.status === 409
                  }
                  onBack={editorBack}
                  onBackReady={receiveBack}
                  reportDirty={reportChildDirty}
                />
              ) : (
                <ClubHallsScreen
                  key={id + ':' + refresh}
                  clubId={id}
                  api={api.halls}
                  clientMode
                  readOnly={
                    !canWrite || state.pending || state.error?.status === 409
                  }
                  onBack={profileBack}
                  onOpenEditor={openEditor}
                  onBackReady={receiveBack}
                  reportDirty={reportChildDirty}
                />
              )}
              {!state.pending && (
                <button className="ob-button" onClick={reload}>
                  Загрузить актуальные данные
                </button>
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
        <button className="ob-button" onClick={back}>
          Вернуться к клубу
        </button>
      )}
    </Shell>
  );
}
