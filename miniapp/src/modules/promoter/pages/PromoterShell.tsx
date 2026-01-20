import { useCallback, useEffect, useMemo, useState } from 'react';
import ToastHost from '../../../widgets/ToastHost';
import { useUiStore } from '../../../shared/store/ui';
import OverviewScreen from './OverviewScreen';
import GuestListsScreen from './GuestListsScreen';
import InvitationsScreen from './InvitationsScreen';
import TablesScreen from './TablesScreen';
import CreativesScreen from './CreativesScreen';

type PromoterTab = 'overview' | 'lists' | 'invitations' | 'tables' | 'creatives';

const parseTab = (): PromoterTab => {
  const params = new URLSearchParams(window.location.search);
  const value = params.get('tab');
  if (value === 'lists' || value === 'invitations' || value === 'tables' || value === 'creatives') {
    return value;
  }
  return 'overview';
};

const parseIdParam = (key: string) => {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get(key);
  const id = raw ? Number(raw) : NaN;
  if (!Number.isFinite(id) || id <= 0) return null;
  return id;
};

const setPromoterParamsInUrl = (params: {
  tab?: PromoterTab;
  clubId?: number | null;
  guestListId?: number | null;
  eventId?: number | null;
}) => {
  const url = new URL(window.location.href);
  if (params.tab) {
    url.searchParams.set('tab', params.tab);
  }
  if (params.clubId) {
    url.searchParams.set('clubId', String(params.clubId));
  } else {
    url.searchParams.delete('clubId');
  }
  if (params.guestListId) {
    url.searchParams.set('guestListId', String(params.guestListId));
  } else {
    url.searchParams.delete('guestListId');
  }
  if (params.eventId) {
    url.searchParams.set('eventId', String(params.eventId));
  } else {
    url.searchParams.delete('eventId');
  }
  window.history.pushState({}, '', url);
};

const removePromoterMode = () => {
  const url = new URL(window.location.href);
  url.searchParams.delete('mode');
  url.searchParams.delete('tab');
  url.searchParams.delete('clubId');
  url.searchParams.delete('guestListId');
  url.searchParams.delete('eventId');
  window.location.assign(url.toString());
};

export default function PromoterShell() {
  const addToast = useUiStore((state) => state.addToast);
  const [tab, setTab] = useState<PromoterTab>(() => parseTab());
  const [clubId, setClubId] = useState<number | null>(() => parseIdParam('clubId'));
  const [guestListId, setGuestListId] = useState<number | null>(() => parseIdParam('guestListId'));
  const [eventId, setEventId] = useState<number | null>(() => parseIdParam('eventId'));
  const [forbidden, setForbidden] = useState(false);
  const [toastShown, setToastShown] = useState(false);

  useEffect(() => {
    const handlePopState = () => {
      setTab(parseTab());
      setClubId(parseIdParam('clubId'));
      setGuestListId(parseIdParam('guestListId'));
      setEventId(parseIdParam('eventId'));
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const handleTabChange = useCallback((next: PromoterTab) => {
    setPromoterParamsInUrl({ tab: next, clubId, guestListId, eventId });
    setTab(next);
    window.scrollTo(0, 0);
  }, [clubId, eventId, guestListId]);

  const handleSelectClub = useCallback((id: number | null) => {
    setPromoterParamsInUrl({ tab, clubId: id, guestListId, eventId });
    setClubId(id);
  }, [eventId, guestListId, tab]);

  const handleSelectGuestList = useCallback((id: number | null) => {
    setPromoterParamsInUrl({ tab, clubId, guestListId: id, eventId });
    setGuestListId(id);
  }, [clubId, eventId, tab]);

  const handleSelectEvent = useCallback((id: number | null) => {
    setPromoterParamsInUrl({ tab, clubId, guestListId, eventId: id });
    setEventId(id);
  }, [clubId, guestListId, tab]);

  const handleForbidden = useCallback(() => {
    setForbidden(true);
    if (!toastShown) {
      addToast('Недостаточно прав');
      setToastShown(true);
    }
  }, [addToast, toastShown]);

  const content = useMemo(() => {
    if (forbidden) {
      return (
        <div className="px-4 py-8 text-center text-sm text-gray-600">
          Нет доступа к кабинету промоутера.
        </div>
      );
    }
    switch (tab) {
      case 'lists':
        return (
          <GuestListsScreen
            clubId={clubId}
            guestListId={guestListId}
            onSelectClub={handleSelectClub}
            onSelectGuestList={handleSelectGuestList}
            onForbidden={handleForbidden}
          />
        );
      case 'invitations':
        return (
          <InvitationsScreen
            guestListId={guestListId}
            onSelectGuestList={handleSelectGuestList}
            onForbidden={handleForbidden}
          />
        );
      case 'tables':
        return (
          <TablesScreen
            clubId={clubId}
            eventId={eventId}
            guestListId={guestListId}
            onSelectClub={handleSelectClub}
            onSelectEvent={handleSelectEvent}
            onSelectGuestList={handleSelectGuestList}
            onForbidden={handleForbidden}
          />
        );
      case 'creatives':
        return <CreativesScreen />;
      case 'overview':
      default:
        return <OverviewScreen onForbidden={handleForbidden} />;
    }
  }, [
    clubId,
    eventId,
    forbidden,
    guestListId,
    handleForbidden,
    handleSelectClub,
    handleSelectEvent,
    handleSelectGuestList,
    tab,
  ]);

  return (
    <div
      className="min-h-screen bg-gray-50 pb-20"
      style={{ paddingBottom: 'calc(5rem + env(safe-area-inset-bottom, 0px))' }}
    >
      <header className="sticky top-0 z-10 flex items-center justify-between bg-white px-4 py-3 shadow-sm">
        <h1 className="text-base font-semibold text-gray-900">Promoter</h1>
        <button type="button" className="text-sm text-blue-600" onClick={removePromoterMode}>
          Выйти
        </button>
      </header>
      <nav className="sticky top-[3.25rem] z-10 flex gap-2 overflow-x-auto bg-white px-4 py-2 text-xs font-medium text-gray-500 shadow-sm">
        {([
          ['overview', 'Обзор'],
          ['lists', 'Списки'],
          ['invitations', 'Приглашения'],
          ['tables', 'Столы'],
          ['creatives', 'Креативы'],
        ] as const).map(([key, label]) => (
          <button
            key={key}
            type="button"
            className={`rounded-full px-3 py-1 ${tab === key ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'}`}
            onClick={() => handleTabChange(key)}
          >
            {label}
          </button>
        ))}
      </nav>
      {content}
      <ToastHost />
    </div>
  );
}
