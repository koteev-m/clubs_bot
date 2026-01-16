import { useCallback, useEffect, useMemo, useState } from 'react';
import GuestHome from './GuestHome';
import MyBookingPage from './MyBookingPage';
import MyNights from '../../mynights/pages/MyNights';
import MusicPage from '../../music/pages/MusicPage';
import ToastHost from '../../../widgets/ToastHost';

type GuestTab = 'book' | 'my_booking' | 'my_nights' | 'music';

type TabConfig = {
  key: GuestTab;
  label: string;
};

const tabs: TabConfig[] = [
  { key: 'book', label: 'Забронировать' },
  { key: 'my_booking', label: 'Моя бронь' },
  { key: 'my_nights', label: 'Мои ночи' },
  { key: 'music', label: 'Музыка' },
];

const resolveTab = (value: string | null): GuestTab => {
  switch (value) {
    case 'book':
    case 'my_booking':
    case 'my_nights':
    case 'music':
      return value;
    default:
      return 'book';
  }
};

const readTabFromUrl = () => {
  const params = new URLSearchParams(window.location.search);
  return resolveTab(params.get('tab'));
};

const syncUrlTab = (tab: GuestTab) => {
  const url = new URL(window.location.href);
  url.searchParams.set('tab', tab);
  window.history.replaceState({}, '', url);
};

export default function GuestShell() {
  const [activeTab, setActiveTab] = useState<GuestTab>(() => readTabFromUrl());

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const rawTab = params.get('tab');
    const normalizedTab = readTabFromUrl();
    if (rawTab !== normalizedTab) {
      syncUrlTab(normalizedTab);
    }
  }, []);

  useEffect(() => {
    const handlePopState = () => {
      setActiveTab(readTabFromUrl());
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  const handleTabChange = useCallback(
    (tab: GuestTab) => {
      if (tab === activeTab) return;
      syncUrlTab(tab);
      setActiveTab(tab);
      window.scrollTo(0, 0);
    },
    [activeTab],
  );

  const content = useMemo(() => {
    switch (activeTab) {
      case 'my_booking':
        return <MyBookingPage />;
      case 'my_nights':
        return <MyNights />;
      case 'music':
        return <MusicPage />;
      case 'book':
      default:
        return <GuestHome />;
    }
  }, [activeTab]);

  return (
    <div
      className="min-h-screen bg-gray-50"
      style={{ paddingBottom: 'calc(5rem + env(safe-area-inset-bottom))' }}
    >
      {content}
      <ToastHost />
      <nav
        className="fixed bottom-0 left-0 right-0 border-t border-gray-200 bg-white"
        style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}
        aria-label="Guest navigation"
      >
        <div className="flex">
          {tabs.map((tab) => {
            const isActive = tab.key === activeTab;
            return (
              <button
                key={tab.key}
                type="button"
                className={`flex-1 px-2 py-3 text-xs font-medium ${
                  isActive ? 'text-blue-600' : 'text-gray-500'
                }`}
                onClick={() => handleTabChange(tab.key)}
                aria-current={isActive ? 'page' : undefined}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
