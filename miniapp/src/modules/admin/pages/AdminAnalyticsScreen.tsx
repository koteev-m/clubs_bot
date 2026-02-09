import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { useUiStore } from '../../../shared/store/ui';
import { http } from '../../../shared/api/http';
import { AdminApiError, AdminClub, listClubs } from '../api/admin.api';
import { AnalyticsMeta, StoryDetailsResponse } from '../api/adminAnalytics.api';
import { useAnalytics } from '../hooks/useAnalytics';
import { useStories } from '../hooks/useStories';
import { NightDto } from '../../../shared/types';
import AuthorizationRequired from '../../../shared/ui/AuthorizationRequired';
import { isRequestCanceled } from '../../../shared/api/error';
import { formatRUB } from '../../../shared/lib/format';

type AnalyticsTab = 'overview' | 'stories';

type AdminAnalyticsScreenProps = {
  clubId: number | null;
  onSelectClub: (clubId: number | null) => void;
  onForbidden: () => void;
};

const formatDateTime = (value?: string) => {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('ru-RU');
};

const formatMinor = (value: number) => formatRUB(value / 100);

const extractStoryMeta = (details: StoryDetailsResponse | null): AnalyticsMeta | null => {
  if (!details || !details.payload || typeof details.payload !== 'object') return null;
  if (!('meta' in details.payload)) return null;
  const meta = (details.payload as { meta?: AnalyticsMeta }).meta;
  if (!meta) return null;
  return meta;
};

export function AnalyticsMetaNotice({ meta }: { meta: AnalyticsMeta }) {
  return (
    <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900 space-y-2">
      <div className="flex items-center justify-between">
        <span className="font-medium">Качество данных</span>
        {meta.hasIncompleteData && <span className="rounded bg-amber-200 px-2 py-0.5">Данные неполные</span>}
      </div>
      {meta.caveats.length > 0 && (
        <div className="space-y-1">
          <div className="text-[11px] uppercase text-amber-700">Оговорки</div>
          <ul className="list-disc space-y-1 pl-4">
            {meta.caveats.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export default function AdminAnalyticsScreen({ clubId, onSelectClub, onForbidden }: AdminAnalyticsScreenProps) {
  const addToast = useUiStore((state) => state.addToast);
  const [tab, setTab] = useState<AnalyticsTab>('overview');
  const [clubs, setClubs] = useState<AdminClub[]>([]);
  const [clubsStatus, setClubsStatus] = useState<'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'>('idle');
  const [clubsError, setClubsError] = useState('');
  const [clubsCanRetry, setClubsCanRetry] = useState(false);
  const [selectedNight, setSelectedNight] = useState('');
  const [nights, setNights] = useState<NightDto[]>([]);
  const [nightsStatus, setNightsStatus] = useState<'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'>('idle');
  const [nightsError, setNightsError] = useState('');
  const [nightsCanRetry, setNightsCanRetry] = useState(false);
  const [isUnauthorized, setIsUnauthorized] = useState(false);
  const [storiesOffset, setStoriesOffset] = useState(0);
  const storiesLimit = 20;
  const clubsAbortRef = useRef<AbortController | null>(null);
  const clubsRequestIdRef = useRef(0);
  const nightsAbortRef = useRef<AbortController | null>(null);
  const nightsRequestIdRef = useRef(0);

  const { status, data, errorMessage, canRetry, reload } = useAnalytics(
    clubId ?? undefined,
    selectedNight || undefined,
    30,
    onForbidden,
  );

  const { list, details, reloadList, reloadDetails } = useStories(
    clubId ?? undefined,
    storiesLimit,
    storiesOffset,
    selectedNight || undefined,
    onForbidden,
  );

  const analyticsMeta = data?.meta;
  const detailsMeta = extractStoryMeta(details.data);

  const attendanceChannel = data?.attendance?.channels.promoterBookings ?? null;
  const showArrivedCaveat = attendanceChannel
    ? attendanceChannel.plannedGuests > 0 && attendanceChannel.arrivedGuests === 0
    : false;

  const segments = useMemo(() => {
    const counts = data?.segments.counts ?? {};
    return {
      new: counts.new ?? 0,
      frequent: counts.frequent ?? 0,
      sleeping: counts.sleeping ?? 0,
    };
  }, [data]);

  const payloadDigest = useMemo(() => {
    if (!details.data) return '';
    return JSON.stringify(details.data.payload, null, 2);
  }, [details.data]);

  const handleClubChange = (value: string) => {
    if (!value) {
      onSelectClub(null);
      return;
    }
    const id = Number(value);
    if (!Number.isFinite(id) || id <= 0) return;
    onSelectClub(id);
  };

  const loadClubs = useCallback(async () => {
    clubsAbortRef.current?.abort();
    const controller = new AbortController();
    clubsAbortRef.current = controller;
    const requestId = ++clubsRequestIdRef.current;
    setClubsStatus('loading');
    setClubsError('');
    setClubsCanRetry(false);
    try {
      const clubsData = await listClubs(controller.signal);
      if (clubsRequestIdRef.current !== requestId) return;
      setClubs(clubsData);
      setClubsStatus('ready');
    } catch (error) {
      if (clubsRequestIdRef.current !== requestId) return;
      if (error instanceof AdminApiError) {
        if (error.status === 401) {
          setIsUnauthorized(true);
          setClubsStatus('unauthorized');
          return;
        }
        if (error.status === 403) {
          onForbidden();
          setClubsError('Нет доступа');
          setClubsStatus('error');
          setClubsCanRetry(false);
          return;
        }
        if (!error.status) {
          setClubsError('Не удалось связаться с сервером');
          setClubsCanRetry(true);
          setClubsStatus('error');
          return;
        }
        if (error.status >= 500) {
          setClubsError('Сервис временно недоступен');
          setClubsCanRetry(true);
          setClubsStatus('error');
          return;
        }
        setClubsError(error.message);
        setClubsStatus('error');
        return;
      }
      setClubsError('Не удалось загрузить список клубов');
      setClubsStatus('error');
    }
  }, [onForbidden]);

  useEffect(() => {
    void loadClubs();
    return () => clubsAbortRef.current?.abort();
  }, [loadClubs]);

  useEffect(() => {
    setSelectedNight('');
    setNights([]);
    setNightsStatus('idle');
    setNightsError('');
    setNightsCanRetry(false);
    setStoriesOffset(0);
  }, [clubId]);

  const loadNights = useCallback(async () => {
    if (!clubId) return;
    nightsAbortRef.current?.abort();
    const controller = new AbortController();
    nightsAbortRef.current = controller;
    const requestId = ++nightsRequestIdRef.current;
    setNightsStatus('loading');
    setNightsError('');
    setNightsCanRetry(false);
    try {
      const response = await http.get<NightDto[]>(`/api/clubs/${clubId}/nights?limit=8`, { signal: controller.signal });
      if (nightsRequestIdRef.current !== requestId) return;
      setNights(response.data);
      setNightsStatus('ready');
      if (!selectedNight && response.data.length > 0) {
        setSelectedNight(response.data[0].startUtc);
      }
    } catch (error) {
      if (isRequestCanceled(error)) return;
      if (nightsRequestIdRef.current !== requestId) return;
      if (axios.isAxiosError(error)) {
        const statusCode = error.response?.status;
        if (statusCode === 401) {
          setIsUnauthorized(true);
          setNightsStatus('unauthorized');
          return;
        }
        if (statusCode === 403) {
          onForbidden();
          setNightsError('Нет доступа');
          setNightsStatus('error');
          setNightsCanRetry(false);
          return;
        }
        if (!statusCode) {
          setNightsError('Не удалось связаться с сервером');
          setNightsCanRetry(true);
          setNightsStatus('error');
          return;
        }
        if (statusCode >= 500) {
          setNightsError('Сервис временно недоступен');
          setNightsCanRetry(true);
          setNightsStatus('error');
          return;
        }
      }
      setNightsError('Не удалось загрузить ночи');
      setNightsStatus('error');
      addToast('Не удалось загрузить ночи');
    } finally {
      if (nightsAbortRef.current === controller) {
        nightsAbortRef.current = null;
      }
    }
  }, [addToast, clubId, onForbidden, selectedNight]);

  useEffect(() => {
    if (!clubId) return;
    void loadNights();
    return () => nightsAbortRef.current?.abort();
  }, [clubId, loadNights]);

  if (isUnauthorized || status === 'unauthorized' || list.status === 'unauthorized' || details.status === 'unauthorized') {
    return <AuthorizationRequired />;
  }

  return (
    <div className="space-y-6 px-4 py-6">
      <div className="space-y-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-lg font-semibold text-gray-900">Аналитика</h2>
            <p className="text-xs text-gray-500">Только агрегированные данные без PII</p>
          </div>
          <div className="flex gap-2 text-xs font-medium">
            <button
              type="button"
              className={`rounded px-3 py-1 ${tab === 'overview' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'}`}
              onClick={() => setTab('overview')}
            >
              Обзор
            </button>
            <button
              type="button"
              className={`rounded px-3 py-1 ${tab === 'stories' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600'}`}
              onClick={() => setTab('stories')}
            >
              Истории
            </button>
          </div>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <label className="text-xs text-gray-500">
            Клуб
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={clubId ?? ''}
              onChange={(event) => handleClubChange(event.target.value)}
              disabled={clubsStatus === 'loading'}
            >
              <option value="">Выберите клуб</option>
              {clubs.map((club) => (
                <option key={club.id} value={club.id}>
                  {club.name}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-gray-500">
            Ночь
            <select
              className="mt-1 w-full rounded-md border border-gray-200 p-2 text-sm"
              value={selectedNight}
              onChange={(event) => setSelectedNight(event.target.value)}
              disabled={!clubId || nightsStatus === 'loading'}
            >
              <option value="">Выберите ночь</option>
              {nights.map((night) => (
                <option key={night.startUtc} value={night.startUtc}>
                  {night.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        {clubsStatus === 'loading' && <div className="text-xs text-gray-500">Загрузка клубов...</div>}
        {clubsStatus === 'error' && (
          <div className="space-y-2 text-xs text-red-600">
            <div>{clubsError || 'Не удалось загрузить клубы'}</div>
            {clubsCanRetry && (
              <button type="button" className="rounded border border-red-200 px-3 py-1" onClick={loadClubs}>
                Повторить
              </button>
            )}
          </div>
        )}
        {nightsStatus === 'loading' && <div className="text-xs text-gray-500">Загрузка ночей...</div>}
        {nightsStatus === 'error' && (
          <div className="space-y-2 text-xs text-red-600">
            <div>{nightsError || 'Не удалось загрузить ночи'}</div>
            {nightsCanRetry && (
              <button type="button" className="rounded border border-red-200 px-3 py-1" onClick={loadNights}>
                Повторить
              </button>
            )}
          </div>
        )}
      </div>

      {tab === 'overview' && (
        <div className="space-y-6">
          {!clubId && <div className="text-sm text-gray-500">Выберите клуб, чтобы увидеть аналитику.</div>}
          {clubId && !selectedNight && <div className="text-sm text-gray-500">Выберите ночь для обзора.</div>}
          {status === 'loading' && <div className="text-sm text-gray-500">Загрузка аналитики...</div>}
          {status === 'error' && (
            <div className="space-y-2 text-sm text-red-600">
              <div>{errorMessage || 'Не удалось загрузить аналитику'}</div>
              {canRetry && (
                <button type="button" className="rounded border border-red-200 px-3 py-1 text-red-600" onClick={reload}>
                  Повторить
                </button>
              )}
            </div>
          )}
          {status === 'forbidden' && <div className="text-sm text-gray-500">Нет доступа к аналитике.</div>}

          {status === 'ready' && data && (
            <div className="space-y-6">
              {analyticsMeta && <AnalyticsMetaNotice meta={analyticsMeta} />}
              <div className="grid gap-4 lg:grid-cols-3">
                <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
                  <div className="text-sm font-semibold text-gray-900">Посещаемость</div>
                  {attendanceChannel ? (
                    <div className="space-y-2 text-sm text-gray-700">
                      <div className="flex items-center justify-between">
                        <span>План</span>
                        <span className="font-semibold">{attendanceChannel.plannedGuests}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span>Не пришли</span>
                        <span className="font-semibold">{attendanceChannel.noShowGuests}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span>Пришли</span>
                        <span className="font-semibold">{attendanceChannel.arrivedGuests}</span>
                      </div>
                      {showArrivedCaveat && (
                        <div className="text-xs text-amber-600">Пришедшие гости промо пока не учтены (arrived=0).</div>
                      )}
                    </div>
                  ) : (
                    <div className="text-sm text-gray-500">Нет данных по посещаемости.</div>
                  )}
                </div>

                <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
                  <div className="text-sm font-semibold text-gray-900">Визиты</div>
                  <div className="space-y-2 text-sm text-gray-700">
                    <div className="flex items-center justify-between">
                      <span>Уникальные посетители</span>
                      <span className="font-semibold">{data.visits.uniqueVisitors}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>Ранние визиты</span>
                      <span className="font-semibold">{data.visits.earlyVisits}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>Ночи с столами</span>
                      <span className="font-semibold">{data.visits.tableNights}</span>
                    </div>
                  </div>
                </div>

                <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
                  <div className="text-sm font-semibold text-gray-900">Депозиты</div>
                  <div className="text-sm text-gray-700">
                    <div className="flex items-center justify-between">
                      <span>Сумма</span>
                      <span className="font-semibold">{formatMinor(data.deposits.totalMinor)}</span>
                    </div>
                  </div>
                  <div className="space-y-2 text-xs text-gray-600">
                    <div className="text-[11px] uppercase text-gray-400">Распределение</div>
                    {Object.keys(data.deposits.allocationSummary).length === 0 && <div>Нет данных.</div>}
                    {Object.entries(data.deposits.allocationSummary).map(([key, value]) => (
                      <div key={key} className="flex items-center justify-between">
                        <span>{key}</span>
                        <span className="font-medium">{formatMinor(value)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              <div className="grid gap-4 lg:grid-cols-2">
                <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
                  <div className="text-sm font-semibold text-gray-900">Смена</div>
                  {data.shift ? (
                    <div className="space-y-2 text-sm text-gray-700">
                      <div className="flex items-center justify-between">
                        <span>Статус</span>
                        <span className="font-semibold">{data.shift.status}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        <span>Выручка</span>
                        <span className="font-semibold">{formatMinor(data.shift.revenueTotalMinor)}</span>
                      </div>
                      <div className="text-xs text-gray-500">
                        Гости: {data.shift.peopleWomen} / {data.shift.peopleMen} / отказ {data.shift.peopleRejected}
                      </div>
                    </div>
                  ) : (
                    <div className="text-sm text-gray-500">Нет данных по смене.</div>
                  )}
                </div>

                <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
                  <div className="text-sm font-semibold text-gray-900">Сегменты</div>
                  <div className="grid gap-2 text-sm text-gray-700">
                    <div className="flex items-center justify-between">
                      <span>NEW</span>
                      <span className="font-semibold">{segments.new}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>FREQUENT</span>
                      <span className="font-semibold">{segments.frequent}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>SLEEPING</span>
                      <span className="font-semibold">{segments.sleeping}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {tab === 'stories' && (
        <div className="grid gap-6 lg:grid-cols-[1.2fr_1fr]">
          <div className="space-y-4">
            <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
              <div className="flex items-center justify-between">
                <div className="text-sm font-semibold text-gray-900">Истории по ночам</div>
                <div className="flex items-center gap-2 text-xs text-gray-500">
                  <button
                    type="button"
                    className="rounded border border-gray-200 px-2 py-1"
                    disabled={storiesOffset === 0}
                    onClick={() => setStoriesOffset((prev) => Math.max(prev - storiesLimit, 0))}
                  >
                    Назад
                  </button>
                  <button
                    type="button"
                    className="rounded border border-gray-200 px-2 py-1"
                    disabled={list.items.length < storiesLimit}
                    onClick={() => setStoriesOffset((prev) => prev + storiesLimit)}
                  >
                    Далее
                  </button>
                </div>
              </div>
              {!clubId && <div className="text-sm text-gray-500">Выберите клуб, чтобы увидеть истории.</div>}
              {clubId && list.status === 'loading' && <div className="text-sm text-gray-500">Загрузка историй...</div>}
              {list.status === 'error' && (
                <div className="space-y-2 text-sm text-red-600">
                  <div>{list.errorMessage || 'Не удалось загрузить истории'}</div>
                  {list.canRetry && (
                    <button type="button" className="rounded border border-red-200 px-3 py-1" onClick={reloadList}>
                      Повторить
                    </button>
                  )}
                </div>
              )}
              {list.status === 'forbidden' && <div className="text-sm text-gray-500">Нет доступа к историям.</div>}
              {list.status === 'ready' && list.items.length === 0 && (
                <div className="text-sm text-gray-500">Историй пока нет.</div>
              )}
              {list.status === 'ready' && list.items.length > 0 && (
                <div className="space-y-2">
                  {list.items.map((story) => (
                    <button
                      type="button"
                      key={story.id}
                      onClick={() => setSelectedNight(story.nightStartUtc)}
                      className={`w-full rounded border px-3 py-2 text-left text-sm transition ${
                        selectedNight === story.nightStartUtc
                          ? 'border-blue-500 bg-blue-50'
                          : 'border-gray-200 bg-white hover:border-blue-200'
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-medium">{formatDateTime(story.nightStartUtc)}</span>
                        <span className="text-xs text-gray-500">{story.status}</span>
                      </div>
                      <div className="mt-1 flex items-center justify-between text-xs text-gray-500">
                        <span>Сформирована: {formatDateTime(story.generatedAt)}</span>
                        {list.incompleteMap[story.nightStartUtc] && (
                          <span className="rounded bg-amber-100 px-2 py-0.5 text-amber-700">Неполные данные</span>
                        )}
                      </div>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="space-y-4">
            <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm space-y-3">
              <div className="text-sm font-semibold text-gray-900">Детали истории</div>
              {!selectedNight && <div className="text-sm text-gray-500">Выберите ночь для просмотра.</div>}
              {selectedNight && details.status === 'loading' && <div className="text-sm text-gray-500">Загрузка деталей...</div>}
              {details.status === 'error' && (
                <div className="space-y-2 text-sm text-red-600">
                  <div>{details.errorMessage || 'Не удалось загрузить детали'}</div>
                  {details.canRetry && (
                    <button type="button" className="rounded border border-red-200 px-3 py-1" onClick={reloadDetails}>
                      Повторить
                    </button>
                  )}
                </div>
              )}
              {details.status === 'forbidden' && <div className="text-sm text-gray-500">Нет доступа к деталям.</div>}
              {details.status === 'ready' && details.data && (
                <div className="space-y-3">
                  {detailsMeta && <AnalyticsMetaNotice meta={detailsMeta} />}
                  <div className="rounded border border-gray-100 bg-gray-50 p-3 text-[11px] text-gray-700">
                    <div className="flex items-center justify-between">
                      <span>Night</span>
                      <span className="font-medium">{formatDateTime(details.data.nightStartUtc)}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>Generated</span>
                      <span className="font-medium">{formatDateTime(details.data.generatedAt)}</span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span>Status</span>
                      <span className="font-medium">{details.data.status}</span>
                    </div>
                  </div>
                  <pre className="max-h-[360px] overflow-auto rounded border border-gray-100 bg-gray-50 p-3 text-[11px] text-gray-700">
                    {payloadDigest}
                  </pre>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
