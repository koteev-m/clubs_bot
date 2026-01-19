import { useCallback, useEffect, useMemo, useState } from 'react';
import ToastHost from '../../../widgets/ToastHost';
import { useUiStore } from '../../../shared/store/ui';
import AdminForbidden from './AdminForbidden';
import ClubsScreen from './ClubsScreen';
import ClubHallsScreen from './ClubHallsScreen';

const parseClubId = () => {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get('clubId');
  const id = raw ? Number(raw) : NaN;
  if (!Number.isFinite(id) || id <= 0) return null;
  return id;
};

const setClubIdInUrl = (clubId: number | null) => {
  const url = new URL(window.location.href);
  if (clubId) {
    url.searchParams.set('clubId', String(clubId));
  } else {
    url.searchParams.delete('clubId');
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
  const [forbidden, setForbidden] = useState(false);
  const [toastShown, setToastShown] = useState(false);

  useEffect(() => {
    const handlePopState = () => {
      setClubId(parseClubId());
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const handleSelectClub = useCallback((id: number) => {
    setClubIdInUrl(id);
    setClubId(id);
    window.scrollTo(0, 0);
  }, []);

  const handleBackToClubs = useCallback(() => {
    setClubIdInUrl(null);
    setClubId(null);
    window.scrollTo(0, 0);
  }, []);

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
    if (clubId) {
      return <ClubHallsScreen clubId={clubId} onBack={handleBackToClubs} />;
    }
    return <ClubsScreen onSelectClub={handleSelectClub} onForbidden={handleForbidden} />;
  }, [clubId, forbidden, handleBackToClubs, handleForbidden, handleSelectClub]);

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
