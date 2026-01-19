import { useCallback, useEffect, useMemo, useState } from 'react';
import ToastHost from '../../../widgets/ToastHost';
import { useUiStore } from '../../../shared/store/ui';
import AdminForbidden from './AdminForbidden';
import ClubsScreen from './ClubsScreen';
import ClubHallsScreen from './ClubHallsScreen';
import HallEditorScreen from './HallEditorScreen';

const parseClubId = () => {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get('clubId');
  const id = raw ? Number(raw) : NaN;
  if (!Number.isFinite(id) || id <= 0) return null;
  return id;
};

const parseHallId = () => {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get('hallId');
  const id = raw ? Number(raw) : NaN;
  if (!Number.isFinite(id) || id <= 0) return null;
  return id;
};

const setAdminParamsInUrl = (clubId: number | null, hallId: number | null) => {
  const url = new URL(window.location.href);
  if (clubId) {
    url.searchParams.set('clubId', String(clubId));
  } else {
    url.searchParams.delete('clubId');
  }
  if (hallId && clubId) {
    url.searchParams.set('hallId', String(hallId));
  } else {
    url.searchParams.delete('hallId');
  }
  window.history.pushState({}, '', url);
};

const removeAdminMode = () => {
  const url = new URL(window.location.href);
  url.searchParams.delete('mode');
  url.searchParams.delete('clubId');
  window.location.assign(url.toString());
};

export default function AdminShell() {
  const addToast = useUiStore((state) => state.addToast);
  const [clubId, setClubId] = useState<number | null>(() => parseClubId());
  const [hallId, setHallId] = useState<number | null>(() => (parseClubId() ? parseHallId() : null));
  const [forbidden, setForbidden] = useState(false);
  const [toastShown, setToastShown] = useState(false);

  useEffect(() => {
    const handlePopState = () => {
      const nextClubId = parseClubId();
      setClubId(nextClubId);
      setHallId(nextClubId ? parseHallId() : null);
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const handleSelectClub = useCallback((id: number) => {
    setAdminParamsInUrl(id, null);
    setClubId(id);
    setHallId(null);
    window.scrollTo(0, 0);
  }, []);

  const handleBackToClubs = useCallback(() => {
    setAdminParamsInUrl(null, null);
    setClubId(null);
    setHallId(null);
    window.scrollTo(0, 0);
  }, []);

  const handleOpenHallEditor = useCallback((id: number) => {
    if (!clubId) return;
    setAdminParamsInUrl(clubId, id);
    setHallId(id);
    window.scrollTo(0, 0);
  }, [clubId]);

  const handleBackToHalls = useCallback(() => {
    if (!clubId) return;
    setAdminParamsInUrl(clubId, null);
    setHallId(null);
    window.scrollTo(0, 0);
  }, [clubId]);

  const handleForbidden = useCallback(() => {
    setForbidden(true);
    if (!toastShown) {
      addToast('Недостаточно прав');
      setToastShown(true);
    }
  }, [addToast, toastShown]);

  const content = useMemo(() => {
    if (forbidden) {
      return <AdminForbidden onExit={removeAdminMode} />;
    }
    if (clubId && hallId) {
      return <HallEditorScreen clubId={clubId} hallId={hallId} onBack={handleBackToHalls} />;
    }
    if (clubId) {
      return <ClubHallsScreen clubId={clubId} onBack={handleBackToClubs} onOpenEditor={handleOpenHallEditor} />;
    }
    return <ClubsScreen onSelectClub={handleSelectClub} onForbidden={handleForbidden} />;
  }, [clubId, forbidden, hallId, handleBackToClubs, handleBackToHalls, handleForbidden, handleOpenHallEditor, handleSelectClub]);

  return (
    <div
      className="min-h-screen bg-gray-50 pb-20"
      style={{ paddingBottom: 'calc(5rem + env(safe-area-inset-bottom, 0px))' }}
    >
      <header className="sticky top-0 z-10 flex items-center justify-between bg-white px-4 py-3 shadow-sm">
        <h1 className="text-base font-semibold text-gray-900">Admin</h1>
        <button type="button" className="text-sm text-blue-600" onClick={removeAdminMode}>
          Выйти
        </button>
      </header>
      {content}
      <ToastHost />
    </div>
  );
}
