import { useCallback, useEffect, useMemo, useState } from 'react';
import ToastHost from '../../../widgets/ToastHost';
import { useUiStore } from '../../../shared/store/ui';
import AdminForbidden from './AdminForbidden';
import ClubsScreen from './ClubsScreen';
import ClubHallsScreen from './ClubHallsScreen';
import HallEditorScreen from './HallEditorScreen';
import PromotersQuotasScreen from './PromotersQuotasScreen';
import ManagerTablesScreen from './ManagerTablesScreen';
import FinanceShiftScreen from './FinanceShiftScreen';

type AdminSection = 'clubs' | 'promoters' | 'tables' | 'finance';

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

const parseSection = (): AdminSection => {
  const params = new URLSearchParams(window.location.search);
  const section = params.get('section');
  if (section === 'promoters') return 'promoters';
  if (section === 'tables') return 'tables';
  if (section === 'finance') return 'finance';
  return 'clubs';
};

const setAdminParamsInUrl = (section: AdminSection, clubId: number | null, hallId: number | null) => {
  const url = new URL(window.location.href);
  if (section === 'promoters') {
    url.searchParams.set('section', 'promoters');
  } else if (section === 'tables') {
    url.searchParams.set('section', 'tables');
  } else if (section === 'finance') {
    url.searchParams.set('section', 'finance');
  } else {
    url.searchParams.delete('section');
  }
  if (clubId) {
    url.searchParams.set('clubId', String(clubId));
  } else {
    url.searchParams.delete('clubId');
  }
  if (hallId && clubId && section === 'clubs') {
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
  url.searchParams.delete('hallId');
  window.location.assign(url.toString());
};

export default function AdminShell() {
  const addToast = useUiStore((state) => state.addToast);
  const [section, setSection] = useState<AdminSection>(() => parseSection());
  const [clubId, setClubId] = useState<number | null>(() => parseClubId());
  const [hallId, setHallId] = useState<number | null>(() => (parseClubId() ? parseHallId() : null));
  const [forbidden, setForbidden] = useState(false);
  const [toastShown, setToastShown] = useState(false);

  useEffect(() => {
    const handlePopState = () => {
      const nextSection = parseSection();
      const nextClubId = parseClubId();
      setSection(nextSection);
      setClubId(nextClubId);
      setHallId(nextSection === 'clubs' && nextClubId ? parseHallId() : null);
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const handleSelectClub = useCallback((id: number) => {
    setAdminParamsInUrl('clubs', id, null);
    setClubId(id);
    setHallId(null);
    window.scrollTo(0, 0);
  }, []);

  const handleBackToClubs = useCallback(() => {
    setAdminParamsInUrl('clubs', null, null);
    setClubId(null);
    setHallId(null);
    window.scrollTo(0, 0);
  }, []);

  const handleOpenHallEditor = useCallback((id: number) => {
    if (!clubId) return;
    setAdminParamsInUrl('clubs', clubId, id);
    setHallId(id);
    window.scrollTo(0, 0);
  }, [clubId]);

  const handleBackToHalls = useCallback(() => {
    if (!clubId) return;
    setAdminParamsInUrl('clubs', clubId, null);
    setHallId(null);
    window.scrollTo(0, 0);
  }, [clubId]);

  const handleSelectClubForPromoters = useCallback((id: number) => {
    if (id <= 0) {
      setAdminParamsInUrl('promoters', null, null);
      setSection('promoters');
      setClubId(null);
      setHallId(null);
      return;
    }
    setAdminParamsInUrl('promoters', id, null);
    setSection('promoters');
    setClubId(id);
    setHallId(null);
    window.scrollTo(0, 0);
  }, []);

  const handleSelectClubForTables = useCallback((id: number | null) => {
    setAdminParamsInUrl('tables', id, null);
    setSection('tables');
    setClubId(id);
    setHallId(null);
    window.scrollTo(0, 0);
  }, []);

  const handleSelectClubForFinance = useCallback((id: number | null) => {
    setAdminParamsInUrl('finance', id, null);
    setSection('finance');
    setClubId(id);
    setHallId(null);
    window.scrollTo(0, 0);
  }, []);

  const handleSwitchSection = useCallback(
    (next: AdminSection) => {
      setSection(next);
      if (next === 'promoters') {
        setAdminParamsInUrl('promoters', clubId, null);
        setHallId(null);
      } else if (next === 'tables') {
        setAdminParamsInUrl('tables', clubId, null);
        setHallId(null);
      } else if (next === 'finance') {
        setAdminParamsInUrl('finance', clubId, null);
        setHallId(null);
      } else {
        setAdminParamsInUrl('clubs', clubId, hallId);
      }
      window.scrollTo(0, 0);
    },
    [clubId, hallId],
  );

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
    if (section === 'promoters') {
      return (
        <PromotersQuotasScreen
          clubId={clubId}
          onSelectClub={handleSelectClubForPromoters}
          onForbidden={handleForbidden}
        />
      );
    }
    if (section === 'tables') {
      return <ManagerTablesScreen clubId={clubId} onSelectClub={handleSelectClubForTables} onForbidden={handleForbidden} />;
    }
    if (section === 'finance') {
      return <FinanceShiftScreen clubId={clubId} onSelectClub={handleSelectClubForFinance} onForbidden={handleForbidden} />;
    }
    if (clubId && hallId) {
      return <HallEditorScreen clubId={clubId} hallId={hallId} onBack={handleBackToHalls} />;
    }
    if (clubId) {
      return <ClubHallsScreen clubId={clubId} onBack={handleBackToClubs} onOpenEditor={handleOpenHallEditor} />;
    }
    return <ClubsScreen onSelectClub={handleSelectClub} onForbidden={handleForbidden} />;
  }, [
    clubId,
    forbidden,
    hallId,
    handleBackToClubs,
    handleBackToHalls,
    handleForbidden,
    handleOpenHallEditor,
    handleSelectClub,
    handleSelectClubForPromoters,
    handleSelectClubForFinance,
    handleSelectClubForTables,
    section,
  ]);

  return (
    <div
      className="min-h-screen bg-gray-50 pb-20"
      style={{ paddingBottom: 'calc(5rem + env(safe-area-inset-bottom, 0px))' }}
    >
      <header className="sticky top-0 z-10 bg-white px-4 py-3 shadow-sm">
        <div className="flex items-center justify-between">
          <h1 className="text-base font-semibold text-gray-900">Admin</h1>
          <button type="button" className="text-sm text-blue-600" onClick={removeAdminMode}>
            Выйти
          </button>
        </div>
        {!forbidden && (
          <div className="mt-3 flex gap-2 text-xs font-medium">
            <button
              type="button"
              className={`rounded px-3 py-1 ${section === 'clubs' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'}`}
              onClick={() => handleSwitchSection('clubs')}
            >
              Клубы и залы
            </button>
            <button
              type="button"
              className={`rounded px-3 py-1 ${section === 'promoters' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'}`}
              onClick={() => handleSwitchSection('promoters')}
            >
              Промоутеры и квоты
            </button>
            <button
              type="button"
              className={`rounded px-3 py-1 ${section === 'tables' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'}`}
              onClick={() => handleSwitchSection('tables')}
            >
              Столы
            </button>
            <button
              type="button"
              className={`rounded px-3 py-1 ${section === 'finance' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'}`}
              onClick={() => handleSwitchSection('finance')}
            >
              Финансы
            </button>
          </div>
        )}
      </header>
      {content}
      <ToastHost />
    </div>
  );
}
